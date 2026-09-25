package dev.starryeye.shopagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

class DiscoveredClientRegistrationRepositoryTest {

    McpAuthorizationDiscovery discovery = mock(McpAuthorizationDiscovery.class);

    OAuth2ClientProperties clientProperties = new OAuth2ClientProperties();

    @BeforeEach
    void 자격증명을_설정한다() {
        OAuth2ClientProperties.Registration registration = new OAuth2ClientProperties.Registration();
        registration.setClientId("shop-agent");
        registration.setClientSecret("shop-agent-secret");
        registration.setAuthorizationGrantType("authorization_code");
        registration.setRedirectUri("{baseUrl}/login/oauth2/code/{registrationId}");
        registration.setScope(java.util.Set.of("openid", "profile"));
        this.clientProperties.getRegistration().put("authserver", registration);
    }

    DiscoveredClientRegistrationRepository repository() {
        return new DiscoveredClientRegistrationRepository(this.discovery,
                new McpAuthorizationProperties(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER),
                this.clientProperties);
    }

    @Test
    void 발견한_엔드포인트로_등록을_만든다() {
        given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
                .willReturn(DiscoveryFixtures.discovered());

        var registration = repository().findByRegistrationId("authserver");

        assertThat(registration).isNotNull();
        assertThat(registration.getClientId()).isEqualTo("shop-agent");
        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(DiscoveryFixtures.ISSUER);
        assertThat(registration.getProviderDetails().getAuthorizationUri())
                .isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/authorize");
        assertThat(registration.getProviderDetails().getTokenUri())
                .isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/token");
        assertThat(registration.getProviderDetails().getJwkSetUri())
                .isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/jwks");
        assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile");
        // RFC 9207 지원 여부는 콜백 검증에서 쓰므로 등록에 실어 둔다.
        assertThat(registration.getProviderDetails().getConfigurationMetadata())
                .containsEntry("authorization_response_iss_parameter_supported", true);
    }

    @Test
    void 모르는_등록_ID_는_null_이다() {
        assertThat(repository().findByRegistrationId("other")).isNull();
    }

    @Test
    void 발견은_한_번만_한다() {
        given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
                .willReturn(DiscoveryFixtures.discovered());
        DiscoveredClientRegistrationRepository repository = repository();

        repository.findByRegistrationId("authserver");
        repository.findByRegistrationId("authserver");
        repository.discovered();

        then(this.discovery).should(times(1)).discover(anyString(), anyString());
    }

    @Test
    void 자격증명이_묶인_issuer_를_discovery_에_넘긴다() {
        given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
                .willReturn(DiscoveryFixtures.discovered());

        repository().findByRegistrationId("authserver");

        then(this.discovery).should().discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER);
    }

    @Test
    void 실패는_캐시하지_않는다() {
        given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
                .willThrow(new McpDiscoveryException("서버가 아직 뜨지 않았다"))
                .willReturn(DiscoveryFixtures.discovered());
        DiscoveredClientRegistrationRepository repository = repository();

        assertThatExceptionOfType(McpDiscoveryException.class)
                .isThrownBy(() -> repository.findByRegistrationId("authserver"));

        assertThat(repository.findByRegistrationId("authserver")).isNotNull();
    }
}
