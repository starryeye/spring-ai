package dev.starryeye.cimd.agent.discovery;

import dev.starryeye.cimd.agent.cimd.ClientMetadataProperties;
import dev.starryeye.cimd.agent.cimd.ClientType;
import dev.starryeye.cimd.agent.config.McpAuthorizationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

class DiscoveredClientRegistrationRepositoryTest {

	static final String CHATGPT = "https://localhost:8172/oauth/client.json";

	static final String CLAUDE = "https://localhost:8172/oauth/public-client.json";

	static final String REDIRECT_URI = "http://localhost:8170/login/oauth2/code/authserver";

	McpAuthorizationDiscovery discovery = mock(McpAuthorizationDiscovery.class);

	DiscoveredClientRegistrationRepository repository(ClientType clientType) {
		return new DiscoveredClientRegistrationRepository(this.discovery,
				new McpAuthorizationProperties(DiscoveryFixtures.RESOURCE, clientType),
				new ClientMetadataProperties(null, null, null, null, null, null, null), "authserver");
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
	void 실패는_캐시하지_않는다() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, ClientAuthenticationMethod.PRIVATE_KEY_JWT))
				.willThrow(new McpDiscoveryException("서버가 아직 뜨지 않았다"))
				.willReturn(DiscoveryFixtures.discovered());
		DiscoveredClientRegistrationRepository repository = repository(ClientType.CHATGPT);

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> repository.findByRegistrationId("authserver"));

		assertThat(repository.findByRegistrationId("authserver")).isNotNull();
	}
}
