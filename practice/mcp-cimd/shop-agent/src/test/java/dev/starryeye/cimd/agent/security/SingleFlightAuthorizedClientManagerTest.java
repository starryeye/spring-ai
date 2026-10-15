package dev.starryeye.cimd.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class SingleFlightAuthorizedClientManagerTest {

	static OAuth2AuthorizeRequest request(String user) {
		return OAuth2AuthorizeRequest.withClientRegistrationId("authserver").principal(user).build();
	}

	@Test
	void 요청이_끝나면_그_사용자의_lock_을_지운다() {
		SingleFlightAuthorizedClientManager manager = new SingleFlightAuthorizedClientManager(request -> null);

		manager.authorize(request("alice"));
		manager.authorize(request("bob"));

		// 사용자가 늘어도 lock map이 계속 커지지 않는다.
		assertThat(manager.lockCount()).isZero();
	}

	@Test
	void 감싼_manager가_실패해도_lock_을_지운다() {
		SingleFlightAuthorizedClientManager manager = new SingleFlightAuthorizedClientManager(request -> {
			throw new IllegalStateException("token endpoint 장애");
		});

		assertThatIllegalStateException().isThrownBy(() -> manager.authorize(request("alice")));

		assertThat(manager.lockCount()).isZero();
	}

	@Test
	void 같은_사용자의_요청은_겹치지_않고_끝나면_lock_을_지운다() throws Exception {
		AtomicInteger running = new AtomicInteger();
		AtomicInteger maxRunning = new AtomicInteger();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		OAuth2AuthorizedClientManager delegate = request -> {
			maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
			firstEntered.countDown();
			try {
				release.await(2, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			running.decrementAndGet();
			return null;
		};
		SingleFlightAuthorizedClientManager manager = new SingleFlightAuthorizedClientManager(delegate);
		ExecutorService requests = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = requests.submit(() -> manager.authorize(request("alice")));
			assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
			Future<?> second = requests.submit(() -> manager.authorize(request("alice")));
			// 두 번째 요청이 같은 lock에서 기다리는 동안에는 항목이 남아 있어야 한다.
			assertThat(manager.lockCount()).isEqualTo(1);

			release.countDown();
			first.get(2, TimeUnit.SECONDS);
			second.get(2, TimeUnit.SECONDS);

			assertThat(maxRunning.get()).isEqualTo(1);
			assertThat(manager.lockCount()).isZero();
		}
		finally {
			release.countDown();
			requests.shutdownNow();
		}
	}
}
