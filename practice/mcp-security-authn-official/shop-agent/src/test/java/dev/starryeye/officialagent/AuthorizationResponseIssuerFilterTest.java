package dev.starryeye.officialagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 9207 — 콜백의 iss 가 요청을 보낸 인가 서버와 같은지 확인한다.
 * mix-up 공격(다른 인가 서버의 응답을 끼워 넣기)을 여기서 막는다.
 */
class AuthorizationResponseIssuerFilterTest {

    static final String ISSUER = "http://localhost:9010";

    HttpSessionOAuth2AuthorizationRequestRepository authorizationRequests =
            new HttpSessionOAuth2AuthorizationRequestRepository();

    MockHttpServletRequest request;

    MockHttpServletResponse response = new MockHttpServletResponse();

    MockFilterChain chain = new MockFilterChain();

    @BeforeEach
    void 인가_요청을_저장해_둔다() {
        this.request = new MockHttpServletRequest("GET", "/login/oauth2/code/authserver");
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(ISSUER + "/oauth2/authorize")
                .clientId("official-shop-agent")
                .redirectUri("http://localhost:8110/login/oauth2/code/authserver")
                .state("state-1")
                .attributes(attributes -> attributes.put(OAuth2ParameterNames.REGISTRATION_ID, "authserver"))
                .build();
        this.authorizationRequests.saveAuthorizationRequest(authorizationRequest, this.request, this.response);
        this.request.setParameter("state", "state-1");
        this.request.setParameter("code", "code-1");
    }

    AuthorizationResponseIssuerFilter filter(boolean issAdvertised) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("authserver")
                .clientId("official-shop-agent")
                .clientSecret("official-shop-agent-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri(ISSUER + "/oauth2/authorize")
                .tokenUri(ISSUER + "/oauth2/token")
                .issuerUri(ISSUER)
                .providerConfigurationMetadata(
                        Map.of(DiscoveredAuthorization.ISS_PARAMETER_SUPPORTED, issAdvertised))
                .build();
        return new AuthorizationResponseIssuerFilter(this.authorizationRequests,
                new InMemoryClientRegistrationRepository(registration), new LoginFailureHandler());
    }

    @Test
    void iss_가_같으면_통과시킨다() throws Exception {
        this.request.setParameter("iss", ISSUER);

        filter(true).doFilter(this.request, this.response, this.chain);

        assertThat(this.chain.getRequest()).isNotNull();
        assertThat(this.response.getStatus()).isEqualTo(200);
    }

    @Test
    void iss_가_다르면_코드를_교환하지_않는다() throws Exception {
        this.request.setParameter("iss", "http://evil.example");

        filter(true).doFilter(this.request, this.response, this.chain);

        assertThat(this.chain.getRequest()).isNull();
        assertThat(this.response.getStatus()).isEqualTo(401);
        // 저장해 둔 인가 요청도 버린다. 같은 state 로 다시 시도하지 못하게 한다.
        assertThat(this.authorizationRequests.loadAuthorizationRequest(this.request)).isNull();
    }

    @Test
    void 지원한다고_광고했는데_iss_가_없으면_거부한다() throws Exception {
        filter(true).doFilter(this.request, this.response, this.chain);

        assertThat(this.chain.getRequest()).isNull();
        assertThat(this.response.getStatus()).isEqualTo(401);
    }

    @Test
    void 광고하지_않은_인가_서버라면_iss_없이도_통과시킨다() throws Exception {
        filter(false).doFilter(this.request, this.response, this.chain);

        assertThat(this.chain.getRequest()).isNotNull();
    }

    @Test
    void 콜백이_아닌_요청은_건드리지_않는다() throws Exception {
        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/");

        filter(true).doFilter(other, this.response, this.chain);

        assertThat(this.chain.getRequest()).isNotNull();
    }
}
