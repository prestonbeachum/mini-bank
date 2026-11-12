package dev.minibank.auth;

import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthController {

	record TokenRequest(@NotBlank @Size(max = 64) String clientId, @NotBlank @Size(max = 256) String clientSecret) {
	}

	record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

	private final TokenService tokens;

	private final Map<String, Object> jwks;

	AuthController(TokenService tokens, RSAKey signingKey) {
		this.tokens = tokens;
		this.jwks = new JWKSet(signingKey.toPublicJWK()).toJSONObject();
	}

	@PostMapping("/v1/auth/token")
	TokenResponse token(@Valid @RequestBody TokenRequest request) {
		var issued = tokens.issue(request.clientId(), request.clientSecret());
		return new TokenResponse(issued.accessToken(), "Bearer", issued.expiresIn());
	}

	@GetMapping("/.well-known/jwks.json")
	Map<String, Object> jwks() {
		return jwks;
	}

}
