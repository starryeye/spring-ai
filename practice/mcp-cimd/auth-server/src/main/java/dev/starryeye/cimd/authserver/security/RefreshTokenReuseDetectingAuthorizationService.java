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
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
 * <p>rotation이 한 번에 하나씩만 일어나게 authorization마다 lock을 둔다.
 * Spring의 refresh 처리는 token으로 grant를 찾고, 새 token을 만들고, 저장한다. 그 사이에 lock이 없다.
 * 같은 refresh token이 거의 동시에 두 번 오면 둘 다 지금의 token으로 grant를 찾아 둘 다 새 token을 받는다.
 * 그러면 one-time-use가 깨지고, 도난 탐지는 진 쪽이 다음에 refresh할 때로 밀린다.
 * 그래서 refresh token으로 grant를 찾으면 그 grant의 lock을 잡고 한 번 더 찾는다.
 * 기다리는 사이 다른 요청이 rotation했다면 두 번째 조회에서 없으므로, 버린 token을 다시 쓴 것으로 보고 grant를 끊는다.
 * 기록해 둔 버린 token이 와서 grant를 끊을 때도 같은 lock 안에서 끊는다. 그래야 rotation 중이던 요청의 저장이 끊은 grant를 되살리지 않는다.
 * lock은 그 grant를 저장할 때 푼다. 저장하지 않고 끝난 요청(검증 실패 등)은 token endpoint의
 * {@link RefreshLockReleasingFilter}가 요청 끝에 푼다. lock을 잡은 요청을 thread로 구별하므로, 한 요청이 한 thread에서 돈다고 본다.
 * 이 lock은 이 process 안에서만 통한다. 서버를 여러 대 두면 저장소의 version 비교 같은 방법이 따로 필요하다.
 *
 * <p>access token은 JWT라서 MCP Server가 Authorization Server에 묻지 않고 검증한다.
 * 그래서 grant를 끊어도 이미 나간 access token은 만료될 때까지 쓸 수 있다.
 */
public class RefreshTokenReuseDetectingAuthorizationService implements OAuth2AuthorizationService {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenReuseDetectingAuthorizationService.class);

	/**
	 * lock을 기다리는 최대 시간이다. 정상이면 다른 요청이 새 token을 만들어 저장하는 짧은 동안만 기다린다.
	 * 풀리지 않은 lock(버그)이 있어도 token 요청이 끝없이 묶이지 않게 상한을 둔다. 넘으면 그 refresh는 실패한다.
	 */
	private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

	private final OAuth2AuthorizationService delegate;

	private final Clock clock;

	/** 버린 refresh token의 hash에서 그 authorization의 id와 버린 token의 만료 시각으로 가는 기록이다. */
	private final Map<String, Retired> retired = new ConcurrentHashMap<>();

	/** authorization id마다의 lock이다. 잡거나 기다리는 thread가 없으면 지운다. */
	private final Map<String, GrantLock> locks = new ConcurrentHashMap<>();

	/** 지금 thread가 잡은 lock의 authorization id다. 같은 요청 안의 두 번째 조회는 다시 잡지 않는다. */
	private final ThreadLocal<Set<String>> held = ThreadLocal.withInitial(HashSet::new);

	private record Retired(String authorizationId, Instant expiresAt) {
	}

	/** {@code users}는 lock을 잡았거나 기다리는 thread 수다. {@link ConcurrentHashMap#compute} 안에서만 읽고 바꾼다. */
	private static final class GrantLock {

		final ReentrantLock lock = new ReentrantLock();

		int users;
	}

	public RefreshTokenReuseDetectingAuthorizationService(OAuth2AuthorizationService delegate, Clock clock) {
		this.delegate = delegate;
		this.clock = clock;
	}

	@Override
	public void save(OAuth2Authorization authorization) {
		try {
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
		finally {
			unlock(authorization.getId());
		}
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
		if (!OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
			return found;
		}
		if (found == null) {
			Retired reused = this.retired.remove(hash(token));
			if (reused != null && reused.expiresAt().isAfter(this.clock.instant())) {
				revokeUnderLock(reused.authorizationId());
			}
			return null;
		}
		if (this.held.get().contains(found.getId())) {
			return found;
		}
		if (!lock(found.getId())) {
			return null;
		}
		OAuth2Authorization current = this.delegate.findByToken(token, tokenType);
		if (current == null) {
			// lock을 기다리는 사이 다른 요청이 이 token을 rotation했다. 버린 token을 다시 쓴 것과 같다.
			this.retired.remove(hash(token));
			revoke(found.getId());
			unlock(found.getId());
		}
		return current;
	}

	/**
	 * 지금 thread가 잡은 lock을 모두 푼다. token endpoint 요청이 끝날 때 {@link RefreshLockReleasingFilter}가 부른다.
	 * 저장까지 간 요청은 저장할 때 이미 풀었으므로, 여기서는 저장하지 않고 끝난 요청의 lock만 남아 있다.
	 */
	public void releaseHeldLocks() {
		for (String authorizationId : List.copyOf(this.held.get())) {
			unlock(authorizationId);
		}
		this.held.remove();
	}

	/** 테스트가 lock 기록이 치워졌는지 보려고 쓴다. */
	int lockCount() {
		return this.locks.size();
	}

	/** 테스트가 기록이 치워졌는지 보려고 쓴다. */
	int retiredCount() {
		return this.retired.size();
	}

	private boolean lock(String authorizationId) {
		GrantLock grantLock = this.locks.compute(authorizationId, (id, existing) -> {
			GrantLock entry = (existing != null) ? existing : new GrantLock();
			entry.users++;
			return entry;
		});
		boolean locked = false;
		try {
			locked = grantLock.lock.tryLock(LOCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		if (!locked) {
			leave(authorizationId);
			log.warn("refresh lock을 {} 안에 잡지 못했다 — 이 refresh를 실패로 끝낸다", LOCK_TIMEOUT);
			return false;
		}
		this.held.get().add(authorizationId);
		return true;
	}

	private void unlock(String authorizationId) {
		if (!this.held.get().remove(authorizationId)) {
			return;
		}
		this.locks.get(authorizationId).lock.unlock();
		leave(authorizationId);
	}

	/** lock을 쓰는 thread 수를 줄이고, 아무도 쓰지 않으면 항목을 지운다. */
	private void leave(String authorizationId) {
		this.locks.computeIfPresent(authorizationId, (id, entry) -> (--entry.users == 0) ? null : entry);
	}

	/**
	 * 그 grant의 lock 안에서 끊는다. 지금의 token으로 rotation 중인 요청이 있으면 그 저장이 끝난 뒤에 지운다.
	 * lock 없이 먼저 지우면, 이어지는 저장이 끊은 grant를 다시 살린다.
	 * lock을 시간 안에 잡지 못해도 끊는 것은 미루지 않는다.
	 */
	private void revokeUnderLock(String authorizationId) {
		boolean alreadyHeld = this.held.get().contains(authorizationId);
		boolean locked = !alreadyHeld && lock(authorizationId);
		try {
			revoke(authorizationId);
		}
		finally {
			if (locked) {
				unlock(authorizationId);
			}
		}
	}

	private void revoke(String authorizationId) {
		OAuth2Authorization grant = this.delegate.findById(authorizationId);
		if (grant != null) {
			this.delegate.remove(grant);
			log.warn("버린 refresh token이 다시 왔다 — grant를 끊는다 (client={}, 사용자={})",
					grant.getRegisteredClientId(), grant.getPrincipalName());
		}
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
