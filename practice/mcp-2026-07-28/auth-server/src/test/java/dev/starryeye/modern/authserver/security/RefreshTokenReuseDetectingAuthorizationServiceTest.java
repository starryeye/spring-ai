package dev.starryeye.modern.authserver.security;

import dev.starryeye.modern.authserver.cimd.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenReuseDetectingAuthorizationServiceTest {

	static final String CLIENT_ID = "https://localhost:8182/oauth/public-client.json";

	static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

	MutableClock clock = new MutableClock(NOW);

	RefreshTokenReuseDetectingAuthorizationService service =
			new RefreshTokenReuseDetectingAuthorizationService(new InMemoryOAuth2AuthorizationService(), this.clock);

	static RegisteredClient client() {
		return RegisteredClient.withId(CLIENT_ID)
				.clientId(CLIENT_ID)
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
				.redirectUri("http://localhost:8180/login/oauth2/code/authserver")
				.build();
	}

	static OAuth2RefreshToken refreshToken(String value) {
		return new OAuth2RefreshToken(value, NOW, NOW.plus(Duration.ofHours(1)));
	}

	static OAuth2Authorization authorization(String refreshToken) {
		return authorization("authorization-1", refreshToken);
	}

	static OAuth2Authorization authorization(String id, String refreshToken) {
		return OAuth2Authorization.withRegisteredClient(client())
				.id(id)
				.principalName("user")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.refreshToken(refreshToken(refreshToken))
				.build();
	}

	/** refresh 한 번: Spring은 같은 authorization의 refresh token만 새것으로 바꿔 저장한다. */
	OAuth2Authorization rotate(OAuth2Authorization current, String next) {
		OAuth2Authorization rotated = OAuth2Authorization.from(current).refreshToken(refreshToken(next)).build();
		this.service.save(rotated);
		return rotated;
	}

	@Test
	void 지금의_refresh_token으로는_grant를_찾는다() {
		this.service.save(authorization("first"));

		assertThat(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
	}

	@Test
	void 버린_refresh_token이_다시_오면_grant_전체를_끊는다() {
		OAuth2Authorization first = authorization("first");
		this.service.save(first);
		rotate(first, "second");

		// 도둑이든 진짜 client든, 버린 token을 내민 쪽이 있으면 도난으로 본다.
		assertThat(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN)).isNull();

		// 지금 쓰던 refresh token과 grant도 함께 없어진다.
		assertThat(this.service.findByToken("second", OAuth2TokenType.REFRESH_TOKEN)).isNull();
		assertThat(this.service.findById("authorization-1")).isNull();
	}

	@Test
	void 모르는_refresh_token은_아무것도_끊지_않는다() {
		this.service.save(authorization("first"));

		assertThat(this.service.findByToken("unknown", OAuth2TokenType.REFRESH_TOKEN)).isNull();
		assertThat(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
	}

	@Test
	void 버린_refresh_token이_만료된_뒤에는_기록을_지운다() {
		OAuth2Authorization first = authorization("first");
		this.service.save(first);
		rotate(first, "second");

		// 버린 token의 만료(1시간)가 지나면 그 token은 어차피 쓸 수 없으므로 기록하지 않는다.
		this.clock.advance(Duration.ofHours(2));
		this.service.save(authorization("authorization-2", "other")); // 저장할 때 만료된 기록을 치운다.

		assertThat(this.service.retiredCount()).isZero();
	}

	/**
	 * 다른 thread에서 refresh token으로 grant를 찾는다. lock을 기다리면 그 thread는 WAITING이 된다.
	 * 기다리지 않고 끝나면 TERMINATED다. 둘 중 하나가 될 때까지 기다린다.
	 */
	static void lock을_기다리거나_끝날_때까지_기다린다(Thread thread) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			Thread.State state = thread.getState();
			if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
					|| state == Thread.State.TERMINATED) {
				return;
			}
			Thread.sleep(10);
		}
	}

	@Test
	void 같은_refresh_token으로_겹쳐_refresh하면_늦은_쪽은_grant를_끊는다() throws Exception {
		OAuth2Authorization first = authorization("first");
		this.service.save(first);

		// 이긴 쪽: grant를 찾았고 아직 새 refresh token을 저장하지 않았다.
		CountDownLatch winnerFound = new CountDownLatch(1);
		CountDownLatch winnerMaySave = new CountDownLatch(1);
		AtomicReference<OAuth2Authorization> winnerSaw = new AtomicReference<>();
		Thread winner = new Thread(() -> {
			OAuth2Authorization found = this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN);
			winnerSaw.set(found);
			winnerFound.countDown();
			try {
				winnerMaySave.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			this.service.save(OAuth2Authorization.from(found).refreshToken(refreshToken("second")).build());
		});
		winner.start();
		assertThat(winnerFound.await(5, TimeUnit.SECONDS)).isTrue();

		// 늦은 쪽: 같은 token으로 찾는다. Spring은 찾은 뒤 저장할 때까지 lock이 없어서, 그대로 두면 둘 다 성공한다.
		AtomicReference<OAuth2Authorization> loserSaw = new AtomicReference<>();
		Thread loser = new Thread(() -> loserSaw.set(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN)));
		loser.start();
		lock을_기다리거나_끝날_때까지_기다린다(loser);

		winnerMaySave.countDown();
		winner.join(5000);
		loser.join(5000);

		assertThat(winnerSaw.get()).isNotNull();
		assertThat(loserSaw.get()).isNull();
		// 어느 쪽이 도둑인지 모르므로, 이긴 쪽이 받은 새 refresh token도 더는 통하지 않는다.
		assertThat(this.service.findByToken("second", OAuth2TokenType.REFRESH_TOKEN)).isNull();
		assertThat(this.service.findById("authorization-1")).isNull();
		this.service.releaseHeldLocks();
		assertThat(this.service.lockCount()).isZero();
	}

	@Test
	void rotation_중에_버린_token이_오면_저장이_끝난_뒤에_끊어_grant가_되살아나지_않는다() throws Exception {
		OAuth2Authorization first = authorization("first");
		this.service.save(first);
		rotate(first, "second");

		// 지금의 token(second)으로 refresh하는 요청이 grant를 찾았고, 아직 저장하지 않았다.
		CountDownLatch rotatorFound = new CountDownLatch(1);
		CountDownLatch rotatorMaySave = new CountDownLatch(1);
		Thread rotator = new Thread(() -> {
			OAuth2Authorization found = this.service.findByToken("second", OAuth2TokenType.REFRESH_TOKEN);
			rotatorFound.countDown();
			try {
				rotatorMaySave.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			this.service.save(OAuth2Authorization.from(found).refreshToken(refreshToken("third")).build());
		});
		rotator.start();
		assertThat(rotatorFound.await(5, TimeUnit.SECONDS)).isTrue();

		// 그 사이 버린 token(first)이 온다. 바로 지우면, 이어지는 저장이 grant를 다시 살린다.
		Thread reuse = new Thread(() -> this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN));
		reuse.start();
		lock을_기다리거나_끝날_때까지_기다린다(reuse);

		rotatorMaySave.countDown();
		rotator.join(5000);
		reuse.join(5000);

		assertThat(this.service.findById("authorization-1")).isNull();
		assertThat(this.service.findByToken("third", OAuth2TokenType.REFRESH_TOKEN)).isNull();
		assertThat(this.service.lockCount()).isZero();
	}

	@Test
	void save가_끝나면_lock을_풀어_다음_refresh가_기다리지_않는다() throws Exception {
		OAuth2Authorization first = authorization("first");
		this.service.save(first);
		OAuth2Authorization found = this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN);
		this.service.save(OAuth2Authorization.from(found).refreshToken(refreshToken("second")).build());

		AtomicReference<OAuth2Authorization> next = new AtomicReference<>();
		Thread other = new Thread(() -> {
			next.set(this.service.findByToken("second", OAuth2TokenType.REFRESH_TOKEN));
			this.service.releaseHeldLocks();
		});
		other.start();
		other.join(2000);

		assertThat(other.isAlive()).isFalse();
		assertThat(next.get()).isNotNull();
		assertThat(this.service.lockCount()).isZero();
	}

	@Test
	void 저장하지_않고_끝난_요청의_lock은_releaseHeldLocks가_푼다() throws Exception {
		this.service.save(authorization("first"));
		// 찾은 뒤 검증에 실패해 저장하지 않은 요청이다(예: scope가 넓다).
		assertThat(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
		assertThat(this.service.lockCount()).isEqualTo(1);

		this.service.releaseHeldLocks();

		assertThat(this.service.lockCount()).isZero();
		AtomicReference<OAuth2Authorization> next = new AtomicReference<>();
		Thread other = new Thread(() -> {
			next.set(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN));
			this.service.releaseHeldLocks();
		});
		other.start();
		other.join(2000);
		assertThat(next.get()).isNotNull();
	}

	@Test
	void 같은_요청_안에서_두_번_찾아도_기다리지_않는다() {
		this.service.save(authorization("first"));

		// client 인증 단계와 refresh 단계가 같은 token으로 한 번씩 찾는다.
		assertThat(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
		assertThat(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();

		this.service.releaseHeldLocks();
		assertThat(this.service.lockCount()).isZero();
	}

	@Test
	void refresh_token이_아닌_종류로_찾을_때는_끊지_않는다() {
		OAuth2Authorization first = authorization("first");
		this.service.save(first);
		rotate(first, "second");

		// introspection이나 type 없는 조회는 refresh 요청이 아니다.
		assertThat(this.service.findByToken("first", null)).isNull();
		assertThat(this.service.findByToken("second", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
	}
}
