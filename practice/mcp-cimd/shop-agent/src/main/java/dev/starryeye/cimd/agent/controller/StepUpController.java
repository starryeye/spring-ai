package dev.starryeye.cimd.agent.controller;

import dev.starryeye.cimd.agent.config.McpSecurityConfig;
import dev.starryeye.cimd.agent.security.StepUpAuthorizationRequestResolver;
import dev.starryeye.cimd.agent.security.StepUpState;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/** 한 번 거절한 scope를 사용자가 명시적으로 다시 요청할 때 쓴다. */
@RestController
public class StepUpController {

    @GetMapping("/step-up/retry")
    public ResponseEntity<Void> retry(@RequestParam String scope, HttpSession session) {
        StepUpState.of(session).retry(List.of(scope.trim().split("\\s+")));
        URI location = UriComponentsBuilder
                .fromPath(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI + "/"
                        + McpSecurityConfig.REGISTRATION_ID)
                .queryParam(StepUpAuthorizationRequestResolver.PARAMETER, scope)
                .encode().build().toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
