package dev.starryeye.modern.authserver.cimd;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

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
 * <p>assertion의 수명과 재사용도 제한한다(RFC 7523 §3은 {@code exp}를 요구하고, 재사용 방지는 서버에 맡긴다).
 * {@code jti}가 있어야 하고, {@code exp}는 지금부터 5분 안이어야 한다.
 * 같은 {@code client_id}와 {@code jti}의 assertion은 만료될 때까지 한 번만 받는다. 새어 나간 assertion을 되풀이해 쓰지 못하게 하기 위해서다.
 * Spring의 client({@code NimbusJwtClientAuthenticationParametersConverter})는 assertion마다 UUID {@code jti}를 넣고 60초 뒤를 {@code exp}로 정하므로 그대로 통과한다.
 * 사용 기록은 메모리에 두고, 만료(시간 오차 60초 포함)가 지난 기록은 치운다.
 *
 * <p>기록 수의 상한은 두 가지다. 누구나 문서와 key를 올려 client가 될 수 있으므로, 한 client가 다른 client의 인증을 막지 못하게 나눈다.
 * client별 상한({@value #MAX_ASSERTIONS_PER_CLIENT})에 닿으면 그 client의 새 assertion만 거절한다(fail-closed).
 * 기록하지 못한 assertion을 받으면 되풀이를 막지 못하기 때문이다. 거절은 상한을 채운 client 자신에게만 돌아간다.
 * 전체 상한({@value #MAX_USED_ASSERTIONS})은 메모리를 지키기 위한 것이라, 넘어도 거절하지 않는다.
 * 지울 때가 된 기록을 먼저 치우고, 그래도 가득하면 만료가 가장 이른 기록부터 지운다.
 * 그러면 지운 기록의 assertion은 만료 전까지 되풀이를 막지 못한다. client를 여러 개 만든 쪽이 전체 상한을 채우면
 * 다른 client의 짧은 assertion 기록이 먼저 지워진다. 그래도 다른 client의 인증을 막는 것보다 낫다고 보고 이 대가를 받아들인다.
 * 여러 대로 운영하면 기록을 공유 저장소에 둬야 한다.
 *
 * <p>decoder는 cache하지 않고 검증할 때마다 새로 만든다.
 * decoder는 key 목록을 들고 있지 않아 만드는 비용이 작다.
 * 이 factory는 인증이 끝나기 전에 호출되므로, cache하면 호출자가 고른 client_id마다 decoder가 쌓인다.
 * key 목록도 검증할 때마다 가져오므로, client가 key를 바꾸면 바로 적용된다.
 */
public final class CimdJwtClientAssertionDecoderFactory implements JwtDecoderFactory<RegisteredClient> {

	/** assertion의 {@code exp}가 지금부터 이보다 멀면 거절한다. */
	static final Duration MAX_ASSERTION_LIFETIME = Duration.ofMinutes(5);

	/** client 하나의 사용 기록 수 상한이다. 닿으면 그 client의 새 assertion만 거절한다. */
	static final int MAX_ASSERTIONS_PER_CLIENT = 1_000;

	/** 전체 사용 기록 수의 상한이다. 메모리를 지키기 위한 값이라, 넘으면 거절하지 않고 만료가 이른 기록부터 지운다. */
	static final int MAX_USED_ASSERTIONS = 10_000;

	/** {@link JwtTimestampValidator}의 기본 시간 오차다. 만료된 assertion도 이만큼은 받으므로 기록도 이만큼 더 둔다. */
	private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

	private final ClientMetadataHttp http;

	private final Clock clock;

	private final UsedAssertions usedAssertions;

	public CimdJwtClientAssertionDecoderFactory(ClientMetadataHttp http) {
		this(http, Clock.systemUTC(), MAX_ASSERTIONS_PER_CLIENT, MAX_USED_ASSERTIONS);
	}

	/** 테스트가 시간과 두 기록 상한을 정할 수 있도록 열어 둔다. */
	CimdJwtClientAssertionDecoderFactory(ClientMetadataHttp http, Clock clock, int maxPerClient, int maxUsedAssertions) {
		this.http = http;
		this.clock = clock;
		this.usedAssertions = new UsedAssertions(maxPerClient, maxUsedAssertions);
	}

	/** 테스트가 기록 수가 전체 상한을 넘지 않는지 보려고 쓴다. */
	int usedAssertionCount() {
		return this.usedAssertions.size();
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

	/**
	 * claim을 모두 검사한 뒤에만 사용 기록을 남긴다. 다른 검사에 실패한 assertion이 기록을 차지하지 않게 하기 위해서다.
	 */
	OAuth2TokenValidator<Jwt> validator(RegisteredClient client) {
		String clientId = client.getClientId();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
		timestamps.setClock(this.clock);
		OAuth2TokenValidator<Jwt> claims = new DelegatingOAuth2TokenValidator<>(
				new JwtClaimValidator<String>(JwtClaimNames.ISS, clientId::equals),
				new JwtClaimValidator<String>(JwtClaimNames.SUB, clientId::equals),
				new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, CimdJwtClientAssertionDecoderFactory::isIssuerOnly),
				new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
				new JwtClaimValidator<String>(JwtClaimNames.JTI, StringUtils::hasText),
				timestamps,
				this::lifetimeWithinLimit);
		return jwt -> {
			OAuth2TokenValidatorResult result = claims.validate(jwt);
			return result.hasErrors() ? result : firstUse(clientId, jwt);
		};
	}

	private OAuth2TokenValidatorResult lifetimeWithinLimit(Jwt jwt) {
		Instant expiresAt = jwt.getExpiresAt();
		if (expiresAt != null && expiresAt.isAfter(this.clock.instant().plus(MAX_ASSERTION_LIFETIME))) {
			return invalid("assertion의 exp가 %s보다 멀다".formatted(MAX_ASSERTION_LIFETIME));
		}
		return OAuth2TokenValidatorResult.success();
	}

	/**
	 * 이 {@code client_id}와 {@code jti}를 처음 보면 기록하고 받는다. 기록이 있고 아직 지울 때가 아니면 거절한다.
	 * 기록은 assertion을 더는 받지 않게 되는 시각({@code exp} + 시간 오차)까지 둔다.
	 */
	private OAuth2TokenValidatorResult firstUse(String clientId, Jwt jwt) {
		Instant forgetAt = jwt.getExpiresAt().plus(CLOCK_SKEW);
		return switch (this.usedAssertions.record(clientId, jwt.getId(), forgetAt, this.clock.instant())) {
			case RECORDED -> OAuth2TokenValidatorResult.success();
			case REUSED -> invalid("이미 쓴 assertion이다(같은 jti)");
			case CLIENT_FULL -> invalid("이 client의 assertion 사용 기록이 상한(%d)에 닿았다"
					.formatted(this.usedAssertions.maxPerClient));
		};
	}

	/**
	 * client별 사용 기록과, 지울 때가 이른 순서로 늘어선 기록 목록이다. 모든 접근을 이 객체로 동기화한다.
	 * 기록마다 한 번의 조회와 정렬된 목록의 추가·삭제라서, token request 하나에 드는 비용은 기록 수의 log에 비례한다.
	 */
	private static final class UsedAssertions {

		enum Outcome { RECORDED, REUSED, CLIENT_FULL }

		private record Entry(Instant forgetAt, String clientId, String jti) {
		}

		private static final Comparator<Entry> BY_FORGET_AT = Comparator.comparing(Entry::forgetAt)
				.thenComparing(Entry::clientId)
				.thenComparing(Entry::jti);

		final int maxPerClient;

		private final int maxTotal;

		/** {@code client_id}에서 그 client의 {@code jti}별 기록으로 가는 표다. 기록이 없는 client는 지운다. */
		private final Map<String, Map<String, Entry>> byClient = new HashMap<>();

		private final TreeSet<Entry> byForgetAt = new TreeSet<>(BY_FORGET_AT);

		UsedAssertions(int maxPerClient, int maxTotal) {
			this.maxPerClient = maxPerClient;
			this.maxTotal = maxTotal;
		}

		synchronized Outcome record(String clientId, String jti, Instant forgetAt, Instant now) {
			while (!this.byForgetAt.isEmpty() && !this.byForgetAt.first().forgetAt().isAfter(now)) {
				remove(this.byForgetAt.first());
			}
			Map<String, Entry> client = this.byClient.get(clientId);
			if (client != null && client.containsKey(jti)) {
				return Outcome.REUSED;
			}
			if (client != null && client.size() >= this.maxPerClient) {
				return Outcome.CLIENT_FULL;
			}
			while (this.byForgetAt.size() >= this.maxTotal) {
				remove(this.byForgetAt.first());
			}
			Entry entry = new Entry(forgetAt, clientId, jti);
			this.byClient.computeIfAbsent(clientId, id -> new HashMap<>()).put(jti, entry);
			this.byForgetAt.add(entry);
			return Outcome.RECORDED;
		}

		synchronized int size() {
			return this.byForgetAt.size();
		}

		private void remove(Entry entry) {
			this.byForgetAt.remove(entry);
			Map<String, Entry> client = this.byClient.get(entry.clientId());
			client.remove(entry.jti());
			if (client.isEmpty()) {
				this.byClient.remove(entry.clientId());
			}
		}
	}

	private static OAuth2TokenValidatorResult invalid(String description) {
		return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT, description, null));
	}

	/** aud에는 이 서버의 issuer 하나만 있어야 한다. issuer는 요청을 받은 Authorization Server의 것이다. */
	private static boolean isIssuerOnly(List<String> audience) {
		String issuer = AuthorizationServerContextHolder.getContext().getIssuer();
		return audience != null && audience.size() == 1 && audience.get(0).equals(issuer);
	}
}
