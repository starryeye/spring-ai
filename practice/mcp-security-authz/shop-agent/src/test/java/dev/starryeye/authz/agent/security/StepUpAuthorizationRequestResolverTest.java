package dev.starryeye.authz.agent.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StepUpAuthorizationRequestResolverTest {

    static final ClientRegistration REGISTRATION = ClientRegistration.withRegistrationId("authserver")
            .clientId("authz-shop-agent").clientSecret("s")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8140/login/oauth2/code/authserver")
            .authorizationUri("http://localhost:9030/oauth2/authorize")
            .tokenUri("http://localhost:9030/oauth2/token")
            .scope("openid", "products:read")
            .build();

    static final OAuth2AuthorizationRequest ORIGINAL = OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("http://localhost:9030/oauth2/authorize")
            .clientId("authz-shop-agent")
            .redirectUri("http://localhost:8140/login/oauth2/code/authserver")
            .scopes(Set.of("openid", "products:read"))
            .state("state-1")
            .additionalParameters(Map.of("resource", "http://localhost:8141/mcp"))
            .build();

    /** 원래 resolver 자리에 고정된 요청을 돌려주는 stub을 둔다. */
    static final OAuth2AuthorizationRequestResolver DELEGATE = new OAuth2AuthorizationRequestResolver() {

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
            return ORIGINAL;
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
            return ORIGINAL;
        }
    };

    InMemoryOAuth2AuthorizedClientService authorizedClients =
            new InMemoryOAuth2AuthorizedClientService(new InMemoryClientRegistrationRepository(REGISTRATION));

    StepUpAuthorizationRequestResolver resolver =
            new StepUpAuthorizationRequestResolver(DELEGATE, this.authorizedClients, "authserver");

    MockHttpServletRequest 요청(String stepUp) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/authserver");
        request.setUserPrincipal(new TestingAuthenticationToken("user", null));
        if (stepUp != null) {
            request.setParameter(StepUpAuthorizationRequestResolver.PARAMETER, stepUp);
        }
        return request;
    }

    @Test
    void step_up이_없으면_원래_요청을_그대로_쓴다() {
        assertThat(this.resolver.resolve(요청(null))).isSameAs(ORIGINAL);
    }

    @Test
    void 지금_가진_scope와_요청한_scope를_합친다() {
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(),
                Instant.now().plusSeconds(300), Set.of("openid", "products:read"));
        this.authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(REGISTRATION, "user", token),
                new TestingAuthenticationToken("user", null));
        MockHttpServletRequest request = 요청("products:write");

        OAuth2AuthorizationRequest stepUp = this.resolver.resolve(request);

        assertThat(stepUp.getScopes()).containsExactlyInAnyOrder("openid", "products:read", "products:write");
        assertThat(stepUp.getAdditionalParameters()).containsEntry("resource", "http://localhost:8141/mcp");
        assertThat(stepUp.getAuthorizationRequestUri()).contains("products:write");
        assertThat(StepUpState.of(request.getSession()).isPending()).isTrue();
    }

    @Test
    void login_기록이_없어도_원래_scope에_요청한_scope를_더한다() {
        OAuth2AuthorizationRequest stepUp = this.resolver.resolve(요청("products:write"), "authserver");

        assertThat(stepUp.getScopes()).containsExactlyInAnyOrder("openid", "products:read", "products:write");
    }
}
