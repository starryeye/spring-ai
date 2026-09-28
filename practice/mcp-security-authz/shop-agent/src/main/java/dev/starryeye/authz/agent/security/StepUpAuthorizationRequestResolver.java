package dev.starryeye.authz.agent.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code step_up} parameter가 있으면, 지금 가진 scope에 요청한 scope를 더해 authorization request를 만든다(안내서 10장).
 *
 * <p>MCP 2026-07-28은 step-up 때 client가 이전 scope와 새 scope를 합쳐 요청하게 한다.
 * 새 scope만 요청하면 새 token에서 조회 권한이 빠진다.
 */
public class StepUpAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    public static final String PARAMETER = "step_up";

    private static final Logger log = LoggerFactory.getLogger(StepUpAuthorizationRequestResolver.class);

    private final OAuth2AuthorizationRequestResolver delegate;

    private final OAuth2AuthorizedClientService authorizedClients;

    private final String registrationId;

    public StepUpAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate,
            OAuth2AuthorizedClientService authorizedClients, String registrationId) {
        this.delegate = delegate;
        this.authorizedClients = authorizedClients;
        this.registrationId = registrationId;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return stepUp(request, this.delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return stepUp(request, this.delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest stepUp(HttpServletRequest request, OAuth2AuthorizationRequest original) {
        String requested = request.getParameter(PARAMETER);
        if (original == null || requested == null || requested.isBlank()) {
            return original;
        }
        List<String> added = List.of(requested.trim().split("\\s+"));
        Set<String> scopes = new LinkedHashSet<>(original.getScopes());
        scopes.addAll(grantedScopes(request));
        scopes.addAll(added);
        StepUpState.of(request.getSession()).start(added);
        log.info("step-up authorization request — 추가 scope={}, 요청 scope={}", added, scopes);
        // from(...)은 이미 만든 주소를 복사하지 않는다. build()가 바뀐 scope로 주소를 다시 만든다.
        return OAuth2AuthorizationRequest.from(original).scopes(scopes).build();
    }

    private Set<String> grantedScopes(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            return Set.of();
        }
        OAuth2AuthorizedClient client = this.authorizedClients.loadAuthorizedClient(this.registrationId,
                principal.getName());
        return (client == null) ? Set.of() : client.getAccessToken().getScopes();
    }
}
