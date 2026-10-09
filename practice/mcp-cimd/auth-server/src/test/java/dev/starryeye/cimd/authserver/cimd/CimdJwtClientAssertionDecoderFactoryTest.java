package dev.starryeye.cimd.authserver.cimd;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CimdJwtClientAssertionDecoderFactoryTest {

	static final String ISSUER = "http://localhost:9060";

	static final String CLIENT_ID = "https://localhost:8172/oauth/client.json";

	static final String JWKS = "https://localhost:8172/oauth/jwks.json";

	static RSAKey key;

	static RSAKey otherKey;

	@BeforeAll
	static void keys() throws JOSEException {
		key = new RSAKeyGenerator(2048).keyID("agent-key").generate();
		// kid가 같아도 key가 다르면 서명이 맞지 않는다.
		otherKey = new RSAKeyGenerator(2048).keyID("agent-key").generate();
	}

	CimdJwtClientAssertionDecoderFactory factory = new CimdJwtClientAssertionDecoderFactory(uri -> {
		if (!uri.toString().equals(JWKS)) {
			throw new InvalidClientMetadataException("JWKS가 없다: " + uri);
		}
		return FetchedDocument.of(new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8), null);
	});

	@BeforeEach
	void context() {
		AuthorizationServerSettings settings = AuthorizationServerSettings.builder().issuer(ISSUER).build();
		AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
			@Override
			public String getIssuer() {
				return ISSUER;
			}

			@Override
			public AuthorizationServerSettings getAuthorizationServerSettings() {
				return settings;
			}
		});
	}

	@AfterEach
	void resetContext() {
		AuthorizationServerContextHolder.resetContext();
	}

	static RegisteredClient client(String jwkSetUrl) {
		ClientSettings.Builder settings = ClientSettings.builder();
		if (jwkSetUrl != null) {
			settings.jwkSetUrl(jwkSetUrl);
		}
		return RegisteredClient.withId(CLIENT_ID)
				.clientId(CLIENT_ID)
				.clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://localhost:8170/login/oauth2/code/authserver")
				.clientSettings(settings.build())
				.build();
	}

	static String assertion(RSAKey signer, String audience) throws JOSEException {
		return assertion(CLIENT_ID, signer, audience);
	}

	static String assertion(String clientId, RSAKey signer, String audience) throws JOSEException {
		return assertion(clientId, signer, List.of(audience), null);
	}

	static String assertion(String clientId, RSAKey signer, List<String> audience, JOSEObjectType type)
			throws JOSEException {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(clientId)
				.subject(clientId)
				.audience(audience)
				.jwtID(UUID.randomUUID().toString())
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plusSeconds(60)))
				.build();
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).type(type).build(), claims);
		jwt.sign(new RSASSASigner(signer));
		return jwt.serialize();
	}

	@Test
	void 문서의_jwks_uri_key로_서명한_assertion을_받는다() throws Exception {
		var jwt = this.factory.createDecoder(client(JWKS)).decode(assertion(key, ISSUER));

		assertThat(jwt.getSubject()).isEqualTo(CLIENT_ID);
	}

	@Test
	void 다른_key로_서명한_assertion은_거절한다() throws Exception {
		String forged = assertion(otherKey, ISSUER);

		assertThatExceptionOfType(JwtException.class)
				.isThrownBy(() -> this.factory.createDecoder(client(JWKS)).decode(forged));
	}

	@Test
	void aud가_이_서버가_아니면_거절한다() throws Exception {
		String elsewhere = assertion(key, "https://other.example");

		assertThatExceptionOfType(JwtException.class)
				.isThrownBy(() -> this.factory.createDecoder(client(JWKS)).decode(elsewhere));
	}

	@Test
	void aud가_token_endpoint면_거절한다() throws Exception {
		// RFC 7523bis: aud는 issuer 하나여야 한다. endpoint 주소를 aud로 쓰는 옛 방식은 받지 않는다.
		String tokenEndpoint = assertion(key, ISSUER + "/oauth2/token");

		assertThatExceptionOfType(JwtException.class)
				.isThrownBy(() -> this.factory.createDecoder(client(JWKS)).decode(tokenEndpoint));
	}

	@Test
	void aud에_issuer와_다른_값이_함께_있으면_거절한다() throws Exception {
		String withOther = assertion(CLIENT_ID, key, List.of(ISSUER, "https://other.example"), null);

		assertThatExceptionOfType(JwtException.class)
				.isThrownBy(() -> this.factory.createDecoder(client(JWKS)).decode(withOther));
	}

	@Test
	void typ이_client_authentication_jwt인_assertion도_받는다() throws Exception {
		String typed = assertion(CLIENT_ID, key, List.of(ISSUER), new JOSEObjectType("client-authentication+jwt"));

		assertThat(this.factory.createDecoder(client(JWKS)).decode(typed).getSubject()).isEqualTo(CLIENT_ID);
	}

	@Test
	void jwks_uri가_없는_client는_invalid_client다() {
		assertThatExceptionOfType(OAuth2AuthenticationException.class)
				.isThrownBy(() -> this.factory.createDecoder(client(null)))
				.satisfies(ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_client"));
	}

	@Test
	void JWKS를_가져오지_못하면_assertion을_거절한다() throws Exception {
		String valid = assertion(key, ISSUER);

		assertThatExceptionOfType(JwtException.class).isThrownBy(() -> this.factory
				.createDecoder(client("https://localhost:8172/oauth/missing.json")).decode(valid));
	}

	@Test
	void decoder는_자기_client_id의_assertion만_받는다() throws Exception {
		// 같은 key로 서명하고 같은 JWKS를 쓰더라도 iss·sub가 다른 client면 거절한다.
		String otherClient = assertion("https://localhost:8172/oauth/other.json", key, ISSUER);

		assertThatExceptionOfType(JwtException.class)
				.isThrownBy(() -> this.factory.createDecoder(client(JWKS)).decode(otherClient));
	}
}
