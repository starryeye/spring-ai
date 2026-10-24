package dev.starryeye.modern.agent.discovery;

import dev.starryeye.modern.agent.cimd.ClientMetadataProperties;
import dev.starryeye.modern.agent.cimd.ClientType;
import dev.starryeye.modern.agent.config.McpAuthorizationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

class DiscoveredClientRegistrationRepositoryTest {

	static final String CHATGPT = "https://localhost:8182/oauth/client.json";

	static final String CLAUDE = "https://localhost:8182/oauth/public-client.json";

	static final String REDIRECT_URI = "http://localhost:8180/login/oauth2/code/authserver";

	McpAuthorizationDiscovery discovery = mock(McpAuthorizationDiscovery.class);

	TestClock clock = new TestClock();

	DiscoveredClientRegistrationRepository repository(ClientType clientType) {
		return new DiscoveredClientRegistrationRepository(this.discovery,
				new McpAuthorizationProperties(DiscoveryFixtures.RESOURCE, clientType),
				new ClientMetadataProperties(null, null, null, null, null, null, null), "authserver");
	}

	DiscoveredClientRegistrationRepository repository(Duration timeout, Duration backoff) {
		return new DiscoveredClientRegistrationRepository(this.discovery,
				new McpAuthorizationProperties(DiscoveryFixtures.RESOURCE, ClientType.CHATGPT),
				new ClientMetadataProperties(null, null, null, null, null, null, null), "authserver",
				timeout, backoff, this.clock);
	}

	@Test
	void ChatGPT형은_문서_주소를_client_id로_쓰고_private_key_jwt로_인증한다() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, ClientAuthenticationMethod.PRIVATE_KEY_JWT))
				.willReturn(DiscoveryFixtures.discovered());

		var registration = repository(ClientType.CHATGPT).findByRegistrationId("authserver");

		assertThat(registration.getClientId()).isEqualTo(CHATGPT);
		assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.PRIVATE_KEY_JWT);
		assertThat(registration.getClientSecret()).isEmpty();
		assertThat(registration.getRedirectUri()).isEqualTo(REDIRECT_URI);
		assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(DiscoveryFixtures.ISSUER);
		assertThat(registration.getProviderDetails().getAuthorizationUri())
				.isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/authorize");
		assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/token");
		assertThat(registration.getProviderDetails().getJwkSetUri()).isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/jwks");
		assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "products:read");
		// RFC 9207 지원 여부는 callback 검증에서 쓰므로 등록에 넣어 둔다.
		assertThat(registration.getProviderDetails().getConfigurationMetadata())
				.containsEntry("authorization_response_iss_parameter_supported", true);
	}

	@Test
	void Claude형은_none으로_인증하는_public_client다() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, ClientAuthenticationMethod.NONE))
				.willReturn(DiscoveryFixtures.discovered());

		var registration = repository(ClientType.CLAUDE).findByRegistrationId("authserver");

		assertThat(registration.getClientId()).isEqualTo(CLAUDE);
		assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
		assertThat(registration.getClientSecret()).isEmpty();
	}

	@Test
	void 모르는_등록_ID_는_null_이다() {
		assertThat(repository(ClientType.CHATGPT).findByRegistrationId("other")).isNull();
	}

	@Test
	void 발견은_한_번만_한다() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, ClientAuthenticationMethod.PRIVATE_KEY_JWT))
				.willReturn(DiscoveryFixtures.discovered());
		DiscoveredClientRegistrationRepository repository = repository(ClientType.CHATGPT);

		repository.findByRegistrationId("authserver");
		repository.findByRegistrationId("authserver");
		repository.discovered();

		then(this.discovery).should(times(1)).discover(anyString(), any());
	}

	@Test
	void 실패하면_backoff_동안은_다시_요청하지_않고_바로_실패한다() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, ClientAuthenticationMethod.PRIVATE_KEY_JWT))
				.willThrow(new McpDiscoveryException("서버가 아직 뜨지 않았다"))
				.willReturn(DiscoveryFixtures.discovered());
		DiscoveredClientRegistrationRepository repository = repository(Duration.ofSeconds(5), Duration.ofSeconds(10));

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> repository.findByRegistrationId("authserver"))
				.withMessageContaining("서버가 아직 뜨지 않았다");

		// login하지 않은 요청이 몰려도 MCP Server로 요청이 다시 나가지 않는다.
		this.clock.advance(Duration.ofSeconds(9));
		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> repository.findByRegistrationId("authserver"))
				.withMessage("discovery가 실패해 2026-10-15T00:00:10Z 전에는 다시 시도하지 않는다: 서버가 아직 뜨지 않았다");
		then(this.discovery).should(times(1)).discover(anyString(), any());

		// backoff가 지나면 다시 시도하고, 성공한 결과는 기억한다.
		this.clock.advance(Duration.ofSeconds(1));
		assertThat(repository.findByRegistrationId("authserver")).isNotNull();
		assertThat(repository.findByRegistrationId("authserver")).isNotNull();
		then(this.discovery).should(times(2)).discover(anyString(), any());
	}

	@Test
	void 느린_discovery_는_기다리는_요청을_lock_에_묶지_않고_시간_안에_실패시킨다() throws Exception {
		CountDownLatch 응답 = new CountDownLatch(1);
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, ClientAuthenticationMethod.PRIVATE_KEY_JWT)).willAnswer(invocation -> {
			응답.await();
			return DiscoveryFixtures.discovered();
		});
		DiscoveredClientRegistrationRepository repository = repository(Duration.ofMillis(300), Duration.ofSeconds(10));
		ExecutorService requests = Executors.newFixedThreadPool(2);
		try {
			Future<Throwable> first = requests.submit(() -> catchThrowable(() -> repository.findByRegistrationId("authserver")));
			Future<Throwable> second = requests.submit(() -> catchThrowable(repository::discovered));

			// lock 안에서 MCP Server를 기다린다면 두 요청 모두 응답이 올 때까지 나오지 못한다.
			assertThat(first.get(3, TimeUnit.SECONDS)).isInstanceOf(McpDiscoveryException.class);
			assertThat(second.get(3, TimeUnit.SECONDS)).isInstanceOf(McpDiscoveryException.class);
			// 동시에 들어온 요청은 discovery 하나를 함께 기다린다.
			then(this.discovery).should(times(1)).discover(anyString(), any());
		}
		finally {
			응답.countDown();
			requests.shutdownNow();
		}
	}

	/** 테스트가 시간을 직접 움직이는 clock이다. */
	static final class TestClock extends Clock {

		private volatile Instant now = Instant.parse("2026-10-15T00:00:00Z");

		void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}
	}
}
