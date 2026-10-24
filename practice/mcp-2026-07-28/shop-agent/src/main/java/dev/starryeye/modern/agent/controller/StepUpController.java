package dev.starryeye.modern.agent.controller;

import dev.starryeye.modern.agent.config.McpSecurityConfig;
import dev.starryeye.modern.agent.security.StepUpAuthorizationRequestResolver;
import dev.starryeye.modern.agent.security.StepUpState;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * 한 번 거절한 scope를 사용자가 명시적으로 다시 요청할 때 쓴다.
 *
 * <p>이 요청은 session의 시도 기록을 지운다.
 * 상태를 바꾸므로 {@code POST}로만 받고, 다른 요청처럼 CSRF token({@code X-XSRF-TOKEN} header)을 검사받는다.
 * {@code GET}이면 다른 사이트가 {@code <img src>} 하나로 사용자 몰래 기록을 지울 수 있다.
 *
 * <p>화면은 {@code fetch}로 보내므로 redirect 대신 다음에 갈 authorization request 주소를 JSON으로 돌려준다.
 * 화면이 그 주소로 이동한다.
 */
@RestController
public class StepUpController {

    @PostMapping("/step-up/retry")
    public Map<String, String> retry(@RequestParam String scope, HttpSession session) {
        StepUpState.of(session).retry(List.of(scope.trim().split("\\s+")));
        String location = UriComponentsBuilder
                .fromPath(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI + "/"
                        + McpSecurityConfig.REGISTRATION_ID)
                .queryParam(StepUpAuthorizationRequestResolver.PARAMETER, scope)
                .encode().build().toUriString();
        return Map.of("url", location);
    }
}
