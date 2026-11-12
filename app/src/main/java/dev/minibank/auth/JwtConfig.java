package dev.minibank.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Asymmetric (RS256) signing: the private key signs, the public key is served at the JWKS path. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    @Bean
    RSAKey signingKey(AuthProperties props) throws Exception {
        KeyPair pair = (props.jwtPrivateKey() == null || props.jwtPrivateKey().isBlank())
                ? generate()
                : fromPem(props.jwtPrivateKey());
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate())
                .build();
        return new RSAKey.Builder(jwk).keyID(jwk.computeThumbprint().toString()).build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey signingKey, AuthProperties props) throws JOSEException {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }

    private static KeyPair generate() throws Exception {
        log.warn("minibank.auth.jwt-private-key is not set: using an ephemeral signing key. "
                + "Tokens will not survive a restart. Never run a shared environment like this.");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static KeyPair fromPem(String pem) throws Exception {
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        BigInteger modulus = privateKey.getModulus();
        RSAPublicKey publicKey =
                (RSAPublicKey) factory.generatePublic(new RSAPublicKeySpec(modulus, privateKey.getPublicExponent()));
        return new KeyPair(publicKey, privateKey);
    }
}
