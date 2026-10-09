package dev.starryeye.cimd.authserver.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * refresh token rotation에서 버린 refresh token이 다시 오면 그 grant 전체를 끊는다(OAuth 2.1 §4.3.1).
 *
 * <p>rotation은 refresh할 때마다 새 refresh token을 주고 옛것을 버린다.
 * 도둑과 진짜 client가 같은 refresh token을 가졌다면, 늦게 온 쪽은 버린 token을 내밀게 된다.
 * Authorization Server는 어느 쪽이 도둑인지 알 수 없으므로, 지금 쓰는 refresh token까지 끊어 도난을 멈춘다.
 * Spring은 authorization마다 지금의 refresh token 하나만 기억해서, 버린 token은 모르는 token처럼 {@code invalid_grant}로 끝난다.
 * 그러면 도둑이 먼저 refresh한 경우 도둑은 계속 rotation하고 진짜 client만 끊긴다.
 *
 * <p>이 서비스는 refresh token이 바뀌어 저장될 때 버린 token의 SHA-256 hash와 그 authorization의 id를 기억한다.
 * token 값 자체는 기억하지 않는다.
 * refresh 요청({@link OAuth2TokenType#REFRESH_TOKEN}으로 찾는 조회)에 그 hash가 오면 authorization을 지운다.
 * 기록은 버린 token이 만료될 때까지만 두고, 저장할 때 만료된 기록을 치운다.
 *
 * <p>access token은 JWT라서 MCP Server가 Authorization Server에 묻지 않고 검증한다.
 * 그래서 grant를 끊어도 이미 나간 access token은 만료될 때까지 쓸 수 있다.
 */
public class RefreshTokenReuseDetectingAuthorizationService implements OAuth2AuthorizationService {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenReuseDetectingAuthorizationService.class);

	private final OAuth2AuthorizationService delegate;

	private final Clock clock;

	/** 버린 refresh token의 hash에서 그 authorization의 id와 버린 token의 만료 시각으로 가는 기록이다. */
	private final Map<String, Retired> retired = new ConcurrentHashMap<>();

	private record Retired(String authorizationId, Instant expiresAt) {
	}

	public RefreshTokenReuseDetectingAuthorizationService(OAuth2AuthorizationService delegate, Clock clock) {
		this.delegate = delegate;
		this.clock = clock;
	}

	@Override
	public void save(OAuth2Authorization authorization) {
		Instant now = this.clock.instant();
		this.retired.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
		OAuth2Authorization previous = this.delegate.findById(authorization.getId());
		OAuth2RefreshToken replaced = (previous != null) ? refreshToken(previous) : null;
		OAuth2RefreshToken current = refreshToken(authorization);
		if (replaced != null && (current == null || !replaced.getTokenValue().equals(current.getTokenValue()))) {
			Instant expiresAt = (replaced.getExpiresAt() != null) ? replaced.getExpiresAt() : now;
			if (expiresAt.isAfter(now)) {
				this.retired.put(hash(replaced.getTokenValue()), new Retired(authorization.getId(), expiresAt));
			}
		}
		this.delegate.save(authorization);
	}

	@Override
	public void remove(OAuth2Authorization authorization) {
		this.delegate.remove(authorization);
	}

	@Override
	public OAuth2Authorization findById(String id) {
		return this.delegate.findById(id);
	}

	@Override
	public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
		OAuth2Authorization found = this.delegate.findByToken(token, tokenType);
		if (found != null || !OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
			return found;
		}
		Retired reused = this.retired.remove(hash(token));
		if (reused != null && reused.expiresAt().isAfter(this.clock.instant())) {
			OAuth2Authorization grant = this.delegate.findById(reused.authorizationId());
			if (grant != null) {
				this.delegate.remove(grant);
				log.warn("버린 refresh token이 다시 왔다 — grant를 끊는다 (client={}, 사용자={})",
						grant.getRegisteredClientId(), grant.getPrincipalName());
			}
		}
		return null;
	}

	/** 테스트가 기록이 치워졌는지 보려고 쓴다. */
	int retiredCount() {
		return this.retired.size();
	}

	private static OAuth2RefreshToken refreshToken(OAuth2Authorization authorization) {
		OAuth2Authorization.Token<OAuth2RefreshToken> token = authorization.getRefreshToken();
		return (token != null) ? token.getToken() : null;
	}

	private static String hash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256을 쓸 수 없다", ex);
		}
	}
}
