package dev.starryeye.modern.authserver.cimd;

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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CimdJwtClientAssertionDecoderFactoryTest {

	static final String ISSUER = "http://localhost:9070";

	static final String CLIENT_ID = "https://localhost:8182/oauth/client.json";

	static final String JWKS = "https://localhost:8182/oauth/jwks.json";

	static RSAKey key;

	static RSAKey otherKey;

	@BeforeAll
	static void keys() throws JOSEException {
		key = new RSAKeyGenerator(2048).keyID("agent-key").generate();
		// kid가 같아도 key가 다르면 signature가 맞지 않는다.
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
				.redirectUri("http://localhost:8180/login/oauth2/code/authserver")
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

	/**
	 * 시간과 jti를 정해 만든 assertion이다.
	 * {@code jti}가 null이면 넣지 않는다.
	 */
	static String assertion(String clientId, Instant issuedAt, Duration lifetime, String jti) throws JOSEException {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.issuer(clientId)
				.subject(clientId)
				.audience(ISSUER)
				.issueTime(Date.from(issuedAt))
				.expirationTime(Date.from(issuedAt.plus(lifetime)));
		if (jti != null) {
			claims.jwtID(jti);
		}
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
				claims.build());
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}

	static RegisteredClient client(String clientId, String jwkSetUrl) {
		return RegisteredClient.from(client(jwkSetUrl)).id(clientId).clientId(clientId).build();
	}

	@Test
	void 같은_assertion을_다시_보내면_거절한다() throws Exception {
		// 새어 나간 assertion을 exp까지 되풀이해 쓰지 못하게, 같은 client_id와 jti는 한 번만 받는다.
		String once = assertion(key, ISSUER);
		this.factory.createDecoder(client(JWKS)).decode(once);

		assertThatExceptionOfType(JwtException.class)
				.isThrownBy(() -> this.factory.createDecoder(client(JWKS)).decode(once))
				.withMessageContaining("jti");
	}

	@Test
	void 다른_client의_같은_jti는_따로_센다() throws Exception {
		String other = "https://localhost:8182/oauth/other.json";
		this.factory.createDecoder(client(JWKS)).decode(assertion(CLIENT_ID, Instant.now(), Duration.ofSeconds(60), "same"));

		assertThat(this.factory.createDecoder(client(other, JWKS))
				.decode(assertion(other, Instant.now(), Duration.ofSeconds(60), "same")).getSubject()).isEqualTo(other);
	}

	@Test
	void jti가_없는_assertion은_거절한다() throws Exception {
		String withoutJti = assertion(CLIENT_ID, Instant.now(), Duration.ofSeconds(60), null);

		assertThatExceptionOfType(JwtException.class)
				.isThrownBy(() -> this.factory.createDecoder(client(JWKS)).decode(withoutJti));
	}

	@Test
	void exp가_5분보다_먼_assertion은_거절한다() throws Exception {
		String longLived = assertion(CLIENT_ID, Instant.now(), Duration.ofMinutes(10), UUID.randomUUID().toString());

		assertThatExceptionOfType(JwtException.class)
				.isThrownBy(() -> this.factory.createDecoder(client(JWKS)).decode(longLived));
		// 5분 안이면 받는다.
		assertThat(this.factory.createDecoder(client(JWKS))
				.decode(assertion(CLIENT_ID, Instant.now(), Duration.ofMinutes(4), UUID.randomUUID().toString())))
				.isNotNull();
	}

	static final String CLIENT_A = "https://a.example/client.json";

	static final String CLIENT_B = "https://b.example/client.json";

	static final String CLIENT_C = "https://c.example/client.json";

	/**
	 * client별 기록 상한과 전체 기록 상한을 작게 잡은 factory다.
	 * 시간은 {@code clock}으로 움직인다.
	 */
	static CimdJwtClientAssertionDecoderFactory 상한이_작은_factory(MutableClock clock, int perClient, int total) {
		return new CimdJwtClientAssertionDecoderFactory(uri -> FetchedDocument
				.of(new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8), null), clock, perClient, total);
	}

	static Jwt 받는다(CimdJwtClientAssertionDecoderFactory factory, String clientId, String assertion) {
		return factory.createDecoder(client(clientId, JWKS)).decode(assertion);
	}

	static void 거절한다(CimdJwtClientAssertionDecoderFactory factory, String clientId, String assertion) {
		assertThatExceptionOfType(JwtException.class).isThrownBy(() -> 받는다(factory, clientId, assertion));
	}

	@Test
	void client별_상한에_닿으면_그_client의_새_assertion만_거절하고_만료된_기록은_치운다() throws Exception {
		MutableClock clock = new MutableClock(Instant.now());
		CimdJwtClientAssertionDecoderFactory small = 상한이_작은_factory(clock, 1, 10);

		받는다(small, CLIENT_A, assertion(CLIENT_A, clock.instant(), Duration.ofSeconds(60), "a1"));
		// A의 기록이 가득 찼다.
		// 기록하지 못한 assertion을 받으면 되풀이를 막지 못하므로 A의 새 assertion은 거절한다.
		거절한다(small, CLIENT_A, assertion(CLIENT_A, clock.instant(), Duration.ofSeconds(60), "a2"));
		// 다른 client는 A가 상한을 채운 것과 상관없이 받는다.
		assertThat(받는다(small, CLIENT_B, assertion(CLIENT_B, clock.instant(), Duration.ofSeconds(60), "b1")))
				.isNotNull();

		// A의 첫 assertion이 만료되고(시간 오차 60초까지 지나) 기록이 치워지면 A도 다시 받는다.
		clock.advance(Duration.ofMinutes(3));
		assertThat(받는다(small, CLIENT_A, assertion(CLIENT_A, clock.instant(), Duration.ofSeconds(60), "a3")))
				.isNotNull();
	}

	@Test
	void 전체_상한을_넘어도_거절하지_않고_만료가_가장_이른_기록부터_지운다() throws Exception {
		MutableClock clock = new MutableClock(Instant.now());
		CimdJwtClientAssertionDecoderFactory small = 상한이_작은_factory(clock, 10, 2);
		String a = assertion(CLIENT_A, clock.instant(), Duration.ofSeconds(60), "same");
		String b = assertion(CLIENT_B, clock.instant(), Duration.ofSeconds(120), "same");
		String c = assertion(CLIENT_C, clock.instant(), Duration.ofSeconds(90), "same");

		받는다(small, CLIENT_A, a);
		받는다(small, CLIENT_B, b);
		// 전체 기록이 가득 찼어도 새 assertion은 받는다.
		// 메모리를 지키려고 만료가 가장 이른 A의 기록을 지운다.
		assertThat(받는다(small, CLIENT_C, c)).isNotNull();
		assertThat(small.usedAssertionCount()).isEqualTo(2);

		// 남은 기록의 재사용은 그대로 막는다.
		거절한다(small, CLIENT_B, b);
		거절한다(small, CLIENT_C, c);
		// 일찍 지운 A의 기록은 되풀이를 막지 못한다.
		// 다른 client의 인증을 막지 않으려고 받아들인 대가다.
		assertThat(받는다(small, CLIENT_A, a)).isNotNull();
		assertThat(small.usedAssertionCount()).isEqualTo(2);
	}

	@Test
	void 같은_client의_같은_jti는_상한과_상관없이_거절한다() throws Exception {
		MutableClock clock = new MutableClock(Instant.now());
		CimdJwtClientAssertionDecoderFactory small = 상한이_작은_factory(clock, 10, 10);
		String once = assertion(CLIENT_A, clock.instant(), Duration.ofSeconds(60), "once");

		받는다(small, CLIENT_A, once);

		거절한다(small, CLIENT_A, once);
		거절한다(small, CLIENT_A, assertion(CLIENT_A, clock.instant(), Duration.ofSeconds(30), "once"));
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
		// RFC 7523bis: aud는 issuer 하나여야 한다.
		// endpoint 주소를 aud로 쓰는 옛 방식은 받지 않는다.
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
				.createDecoder(client("https://localhost:8182/oauth/missing.json")).decode(valid));
	}

	@Test
	void decoder는_자기_client_id의_assertion만_받는다() throws Exception {
		// 같은 key로 sign하고 같은 JWKS를 쓰더라도 iss·sub가 다른 client면 거절한다.
		String otherClient = assertion("https://localhost:8182/oauth/other.json", key, ISSUER);

		assertThatExceptionOfType(JwtException.class)
				.isThrownBy(() -> this.factory.createDecoder(client(JWKS)).decode(otherClient));
	}
}
