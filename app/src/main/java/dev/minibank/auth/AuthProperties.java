package dev.minibank.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwtPrivateKey PKCS#8 PEM RSA private key. Empty locally: an ephemeral key is generated
 * at startup. In AWS it comes from SSM Parameter Store (build doc section 8).
 * @param bootstrapClients ops and service clients created or rotated at startup
 */
@ConfigurationProperties("minibank.auth")
public record AuthProperties(String issuer, Duration tokenTtl, String jwtPrivateKey,
		List<BootstrapClient> bootstrapClients) {

	public AuthProperties {
		issuer = issuer == null ? "mini-bank" : issuer;
		tokenTtl = tokenTtl == null ? Duration.ofMinutes(15) : tokenTtl;
		bootstrapClients = bootstrapClients == null ? List.of() : bootstrapClients;
	}

	public record BootstrapClient(String clientId, String secret, Role role) {
	}

}
