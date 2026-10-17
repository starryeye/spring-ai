package dev.starryeye.modern.mcpserver;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.client.RestTemplate;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

/**
 * 테스트 안에서 만든 RSA key로 access token을 서명한다.
 *
 * <p>MCP Server는 운영 설정 그대로 {@code issuer-uri}와 {@code audiences}로 token을 검증한다.
 * 다만 Authorization Server를 띄우지 않으려고, metadata와 JWKS를 받아 오는 자리에 {@link StubAuthorizationServer}를 둔다.
 * JWKS에는 이 key의 public key가 들어 있다.
 */
public final class TestTokens {

	public static final String ISSUER = "http://localhost:9070";

	static final String KEY_ID = "test-key";

	static final RSAKey KEY = rsaKey();

	private TestTokens() {
	}

	/** 새 RSA key다. kid는 {@link #KEY}와 같아서, 서명만 맞지 않는 token을 만들 때 쓴다. */
	static RSAKey rsaKey() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			var keyPair = generator.generateKeyPair();
			return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
					.privateKey((RSAPrivateKey) keyPair.getPrivate())
					.keyID(KEY_ID)
					.build();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** {@code subject}가 {@code audience}에 쓰라고 받은 token이다. 5분 동안 쓸 수 있다. */
	public static String token(String subject, String audience, String... scopes) {
		return token(ISSUER, subject, audience, Instant.now().plusSeconds(300), KEY, scopes);
	}

	/**
	 * claim을 하나씩 정해 token을 만든다.
	 * {@code subject}가 {@code null}이면 {@code sub} claim을 넣지 않는다.
	 */
	public static String token(String issuer, String subject, String audience, Instant expiresAt, RSAKey key,
			String... scopes) {
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.audience(List.of(audience))
				.issuedAt(expiresAt.minusSeconds(600))
				.expiresAt(expiresAt)
				.claim("client_id", "https://localhost:8182/oauth/client.json")
				.claim("scope", List.of(scopes));
		if (subject != null) {
			claims.subject(subject);
		}
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims.build())).getTokenValue();
	}

	/**
	 * JWT decoder가 Authorization Server에서 metadata와 JWKS를 받아 오는 자리에 가짜 응답을 둔다.
	 * 운영 설정(issuer-uri와 audiences)은 그대로 두고 network만 끊는다.
	 * 그래서 실제로 켜지는 검증기(issuer, audience, 만료, signature)를 그대로 시험한다.
	 */
	@TestConfiguration
	public static class StubAuthorizationServer {

		@Bean
		JwkSetUriJwtDecoderBuilderCustomizer stubbedAuthorizationServer() {
			RestTemplate restTemplate = new RestTemplate((uri, method) -> {
				String body = uri.getPath().endsWith("/jwks")
						? new JWKSet(KEY.toPublicJWK()).toString()
						: "{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"" + ISSUER + "/oauth2/jwks\"}";
				MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
				MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(), HttpStatus.OK);
				response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
				request.setResponse(response);
				return request;
			});
			return builder -> builder.restOperations(restTemplate);
		}
	}
}
