package dev.starryeye.official.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * login 실패 응답에는 고정 문장과 OAuth 오류 코드만 쓴다.
 * 예외 메시지에는 callback에 공격자가 넣은 값(예: {@code iss})이 들어 있을 수 있다.
 */
class LoginFailureHandlerTest {

    LoginFailureHandler handler = new LoginFailureHandler();

    @Test
    void 응답_본문에는_오류_코드만_쓰고_공격자가_넣은_값은_쓰지_않는다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_request",
                        "iss mismatch: expected http://localhost:9010 but got http://evil.example\r\nINJECTED", null)));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getContentAsString())
                .startsWith("로그인 실패: invalid_request")
                .doesNotContain("evil.example")
                .doesNotContain("INJECTED");
    }

    @Test
    void 오류_코드의_모양이_OAuth_오류_코드가_아니면_본문에_쓰지_않는다() throws Exception {
        // callback의 error parameter도 공격자가 정할 수 있다.
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("<b>evil</b>\r\nINJECTED")));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .startsWith("로그인 실패")
                .doesNotContain("evil")
                .doesNotContain("INJECTED");
    }

    @Test
    void 로그에_남길_값은_제어_문자를_바꾸고_길이를_자른다() {
        assertThat(LoginFailureHandler.forLog("got http://evil.example\r\n2026-10-15 INFO 가짜 줄"))
                .isEqualTo("got http://evil.example??2026-10-15 INFO 가짜 줄");
        assertThat(LoginFailureHandler.forLog("a b")).isEqualTo("a?b");
        assertThat(LoginFailureHandler.forLog("x".repeat(500)))
                .startsWith("x".repeat(200))
                .endsWith("...(300자 생략)");
        assertThat(LoginFailureHandler.forLog(null)).isEqualTo("null");
    }
}
