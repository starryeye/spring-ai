package dev.starryeye.cimd.authserver.cimd;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * {@code private_key_jwt} client의 assertion을 문서의 {@code jwks_uri}에 있는 public key로 검증한다.
 *
 * <p>Spring의 {@code JwtClientAssertionDecoderFactory}는 JVM 기본 truststore를 쓰는 내부 HTTP client로 JWKS를 받는다.
 * 그래서 self-signed 인증서의 문서 host를 읽지 못하고, 주소 검사도 거치지 않는다.
 * 이 factory는 문서를 가져올 때와 같은 {@link ClientMetadataHttp}로 JWKS를 받는다.
 *
 * <p>iss·sub가 client_id이고 만료되지 않았는지는 Spring의 기본 규칙과 같게 본다.
 * aud는 RFC 7523bis대로 이 서버의 issuer 하나만 받는다.
 * Spring의 기본 규칙은 issuer와 token·introspection·revocation·PAR endpoint 가운데 하나만 있어도 받는다.
 * 그러면 다른 서버를 audience로 함께 넣은 assertion이나, 다른 서버의 endpoint 주소를 속여 받아 낸 assertion이 통할 수 있다(audience injection).
 * {@code typ} header는 보지 않는다. 7523bis는 {@code client-authentication+jwt}를 권하지만, typ이 없는 assertion을 거절하지 말라고 한다.
 *
 * <p>decoder는 cache하지 않고 검증할 때마다 새로 만든다.
 * decoder는 key 목록을 들고 있지 않아 만드는 비용이 작다.
 * 이 factory는 인증이 끝나기 전에 호출되므로, cache하면 호출자가 고른 client_id마다 decoder가 쌓인다.
 * key 목록도 검증할 때마다 가져오므로, client가 key를 바꾸면 바로 적용된다.
 */
public final class CimdJwtClientAssertionDecoderFactory implements JwtDecoderFactory<RegisteredClient> {

	private final ClientMetadataHttp http;

	public CimdJwtClientAssertionDecoderFactory(ClientMetadataHttp http) {
		this.http = http;
	}

	@Override
	public JwtDecoder createDecoder(RegisteredClient client) {
		String jwkSetUrl = client.getClientSettings().getJwkSetUrl();
		if (!StringUtils.hasText(jwkSetUrl)) {
			throw new OAuth2AuthenticationException(
					new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT, "client 문서에 jwks_uri가 없다", null));
		}
		return build(client, jwkSetUrl);
	}

	private JwtDecoder build(RegisteredClient client, String jwkSetUrl) {
		URI uri = URI.create(jwkSetUrl);
		JWKSource<SecurityContext> keys = (selector, context) -> {
			try {
				return selector.select(JWKSet.parse(new String(this.http.get(uri).body(), StandardCharsets.UTF_8)));
			}
			catch (InvalidClientMetadataException | ParseException ex) {
				throw new KeySourceException("client의 JWKS를 가져오지 못했다: " + jwkSetUrl, ex);
			}
		};
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys).jwsAlgorithm(SignatureAlgorithm.RS256).build();
		decoder.setJwtValidator(validator(client));
		return decoder;
	}

	static OAuth2TokenValidator<Jwt> validator(RegisteredClient client) {
		String clientId = client.getClientId();
		return new DelegatingOAuth2TokenValidator<>(
				new JwtClaimValidator<String>(JwtClaimNames.ISS, clientId::equals),
				new JwtClaimValidator<String>(JwtClaimNames.SUB, clientId::equals),
				new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, CimdJwtClientAssertionDecoderFactory::isIssuerOnly),
				new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
				new JwtTimestampValidator());
	}

	/** aud에는 이 서버의 issuer 하나만 있어야 한다. issuer는 요청을 받은 Authorization Server의 것이다. */
	private static boolean isIssuerOnly(List<String> audience) {
		String issuer = AuthorizationServerContextHolder.getContext().getIssuer();
		return audience != null && audience.size() == 1 && audience.get(0).equals(issuer);
	}
}
