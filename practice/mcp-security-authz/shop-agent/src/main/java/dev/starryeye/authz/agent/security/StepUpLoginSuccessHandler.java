package dev.starryeye.authz.agent.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * login이 끝나면 step-up 기다림을 끝내고, 받은 scope를 기록으로 남긴다.
 *
 * <p>사용자는 consent 화면에서 새 scope를 체크하지 않을 수 있다.
 * 그러면 Authorization Server는 이전 scope만 담은 token을 준다(MCP Security Best Practices — down-scoping).
 * 그 경우도 login은 성공이므로 채팅 화면으로 돌아간다. 다음 {@code 403}에는 카드 대신 거절 안내가 나간다.
 */
public class StepUpLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(StepUpLoginSuccessHandler.class);

    private final OAuth2AuthorizedClientService authorizedClients;

    private final String registrationId;

    public StepUpLoginSuccessHandler(OAuth2AuthorizedClientService authorizedClients, String registrationId) {
        this.authorizedClients = authorizedClients;
        this.registrationId = registrationId;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        StepUpState state = StepUpState.existing(request.getSession(false));
        if (state != null && state.isPending()) {
            List<String> requested = state.finish();
            OAuth2AuthorizedClient client = this.authorizedClients.loadAuthorizedClient(this.registrationId,
                    authentication.getName());
            Set<String> granted = (client == null) ? Set.of() : client.getAccessToken().getScopes();
            List<String> missing = requested.stream().filter(scope -> !granted.contains(scope)).toList();
            if (missing.isEmpty()) {
                log.info("step-up 완료 — 사용자={}, 받은 scope={}", authentication.getName(), granted);
            }
            else {
                log.info("step-up에서 허락받지 못한 scope — 사용자={}, 빠진 scope={}", authentication.getName(), missing);
            }
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
