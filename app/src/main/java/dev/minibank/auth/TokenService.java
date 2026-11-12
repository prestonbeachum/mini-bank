package dev.minibank.auth;

import java.time.Clock;
import java.time.Instant;

import com.nimbusds.jose.jwk.RSAKey;
import dev.minibank.common.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

	public record IssuedToken(String accessToken, long expiresIn) {
	}

	private final ApiClientRepository clients;

	private final PasswordEncoder passwords;

	private final JwtEncoder encoder;

	private final RSAKey signingKey;

	private final AuthProperties props;

	private final Clock clock;

	/** Compared against when the client id is unknown, so response time does not reveal which ids exist. */
	private final String dummyHash;

	public TokenService(ApiClientRepository clients, PasswordEncoder passwords, JwtEncoder encoder, RSAKey signingKey,
			AuthProperties props, Clock clock) {
		this.clients = clients;
		this.passwords = passwords;
		this.encoder = encoder;
		this.signingKey = signingKey;
		this.props = props;
		this.clock = clock;
		this.dummyHash = passwords.encode("not-a-real-secret");
	}

	public IssuedToken issue(String clientId, String clientSecret) {
		var client = clients.findActive(clientId);
		boolean matches = passwords.matches(clientSecret, client.map(c -> c.secretHash()).orElse(dummyHash));
		if (client.isEmpty() || !matches) {
			throw ApiException.invalidCredentials();
		}
		var c = client.get();
		Instant now = clock.instant();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
			.issuer(props.issuer())
			.subject(c.clientId())
			.issuedAt(now)
			.expiresAt(now.plus(props.tokenTtl()))
			.claim("role", c.role().claim());
		if (c.customerId() != null) {
			claims.claim("customer_id", c.customerId().toString());
		}
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
		return new IssuedToken(token, props.tokenTtl().toSeconds());
	}

}
