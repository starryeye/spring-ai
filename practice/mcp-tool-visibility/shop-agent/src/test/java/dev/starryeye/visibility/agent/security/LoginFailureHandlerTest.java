package dev.starryeye.visibility.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoginFailureHandlerTest {

    LoginFailureHandler handler = new LoginFailureHandler();

    @Test
    void step_up_중에_거절되면_채팅_화면으로_돌아간다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        StepUpState.of(request.getSession()).start(List.of("products:write"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(StepUpState.of(request.getSession()).isPending()).isFalse();
    }

    @Test
    void 처음_login이_실패하면_이유를_401로_보여_준다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).startsWith("로그인 실패:");
    }
}
