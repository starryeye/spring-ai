package dev.starryeye.stateless.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StepUpLoginSuccessHandlerTest {

    InMemoryOAuth2AuthorizedClientService authorizedClients = new InMemoryOAuth2AuthorizedClientService(
            new InMemoryClientRegistrationRepository(StepUpAuthorizationRequestResolverTest.REGISTRATION));

    StepUpLoginSuccessHandler handler = new StepUpLoginSuccessHandler(this.authorizedClients, "authserver");

    void 새_token(String... scopes) {
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(),
                Instant.now().plusSeconds(300), Set.of(scopes));
        this.authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(
                StepUpAuthorizationRequestResolverTest.REGISTRATION, "user", token), new TestingAuthenticationToken("user", null));
    }

    @Test
    void step_up_login이_끝나면_기다림을_끝내고_채팅_화면으로_돌아간다() throws Exception {
        새_token("openid", "products:read", "products:write");
        MockHttpServletRequest request = new MockHttpServletRequest();
        StepUpState.of(request.getSession()).start(List.of("products:write"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken("user", null));

        assertThat(StepUpState.of(request.getSession()).isPending()).isFalse();
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void 허락받지_못한_scope가_있어도_채팅_화면으로_돌아간다() throws Exception {
        새_token("openid", "products:read");
        MockHttpServletRequest request = new MockHttpServletRequest();
        StepUpState.of(request.getSession()).start(List.of("products:write"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken("user", null));

        // 시도 기록이 남아 있어서, 다음 403에는 카드 대신 거절 안내가 나간다.
        assertThat(StepUpState.of(request.getSession()).attempted(List.of("products:write"))).isTrue();
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void 보통_login은_StepUpState가_없어도_채팅_화면으로_돌아간다() throws Exception {
        새_token("openid", "products:read");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken("user", null));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }
}
