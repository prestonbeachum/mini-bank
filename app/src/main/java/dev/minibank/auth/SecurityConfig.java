package dev.minibank.auth;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Coarse role gates per route. Ownership ("is this your account?") is checked in the service
 * layer, where a foreign id returns 404 rather than 403.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		JwtAuthenticationConverter roles = new JwtAuthenticationConverter();
		roles.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority(
				"ROLE_" + Role.fromClaim(jwt.getClaimAsString("role")).name())));

		AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
			response.setHeader("WWW-Authenticate", "Bearer");
			writeProblem(response, 401, "unauthorized", "Unauthorized", "Missing, expired or invalid bearer token");
		};
		AccessDeniedHandler forbidden = (request, response, ex) -> writeProblem(response, 403, "forbidden",
				"Forbidden", "This role may not call this operation");

		http.csrf(csrf -> csrf.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.GET, "/health", "/health/**", "/.well-known/jwks.json").permitAll()
				.requestMatchers(HttpMethod.POST, "/v1/auth/token").permitAll()
				.requestMatchers("/error").permitAll()
				.requestMatchers(HttpMethod.POST, "/v1/customers").hasRole("OPS")
				.requestMatchers(HttpMethod.POST, "/v1/accounts").hasAnyRole("CUSTOMER", "OPS")
				.requestMatchers(HttpMethod.GET, "/v1/accounts/**").hasAnyRole("CUSTOMER", "OPS")
				.requestMatchers(HttpMethod.POST, "/v1/deposits").hasRole("SERVICE")
				.requestMatchers(HttpMethod.POST, "/v1/transfers").hasAnyRole("CUSTOMER", "SERVICE")
				.anyRequest().denyAll())
			.oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(roles))
				.authenticationEntryPoint(unauthorized)
				.accessDeniedHandler(forbidden))
			.exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden));
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	/** Creates or rotates the configuration-provisioned ops and service clients after Flyway has run. */
	@Bean
	ApplicationRunner bootstrapClients(AuthProperties props, ApiClientRepository clients, PasswordEncoder passwords) {
		return args -> props.bootstrapClients().forEach(c -> {
			if (c.secret() == null || c.secret().isBlank()) {
				return;
			}
			if (c.role() == Role.CUSTOMER) {
				throw new IllegalStateException("Customer clients are issued through POST /v1/customers");
			}
			clients.upsertSystemClient(c.clientId(), passwords.encode(c.secret()), c.role());
		});
	}

	private static void writeProblem(HttpServletResponse response, int status, String slug, String title,
			String detail) throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.getWriter()
			.write("{\"type\":\"https://minibank.dev/problems/%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"}"
				.formatted(slug, title, status, detail));
	}

}
