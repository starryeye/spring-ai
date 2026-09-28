package dev.starryeye.authz.agent.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code step_up} parameter가 있으면, 지금 가진 scope에 요청한 scope를 더해 authorization request를 만든다(안내서 10장).
 *
 * <p>MCP 2026-07-28은 step-up 때 client가 이전 scope와 새 scope를 합쳐 요청하게 한다.
 * 새 scope만 요청하면 새 token에서 조회 권한이 빠진다.
 *
 * <p>이 filter는 {@code OAuth2AuthorizationRequestRedirectFilter} 안에서 도는데, 그 filter는
 * {@code SecurityContextHolderFilter}(session에서 인증 정보를 꺼내는 filter) <b>다음</b>에 있다.
 * 그래서 {@link HttpServletRequest#getUserPrincipal()}이 아니라
 * {@link SecurityContextHolder}에서 인증 정보를 읽어야 한다. {@code getUserPrincipal()}은 이 자리에서
 * 늘 {@code null}이다({@code SecurityContextHolderAwareRequestFilter}가 그보다 뒤에 있다).
 *
 * <p>{@code step_up} 값은 MCP Server가 실제로 {@code 403 insufficient_scope}로 요구한
 * scope({@link StepUpState#challenge})로만 좁힌다. 그렇지 않으면 다른 사이트가 이 GET
 * endpoint를 {@code <img>} 같은 subresource로 불러 임의의 scope로 step-up을 시작시킬 수 있다.
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
        StepUpState state = StepUpState.existing(request.getSession(false));
        List<String> added = (state == null) ? List.of()
                : Arrays.stream(requested.trim().split("\\s+")).filter(state::challenged).distinct().toList();
        if (added.isEmpty()) {
            log.warn("step-up 요청을 무시한다 — MCP Server가 요구한 적 없는 scope다: {}", requested);
            return original;
        }
        Set<String> scopes = new LinkedHashSet<>(original.getScopes());
        scopes.addAll(grantedScopes());
        scopes.addAll(added);
        state.start(added);
        log.info("step-up authorization request — 추가 scope={}, 요청 scope={}", added, scopes);
        // from(...)은 이미 만든 주소를 복사하지 않는다. build()가 바뀐 scope로 주소를 다시 만든다.
        return OAuth2AuthorizationRequest.from(original).scopes(scopes).build();
    }

    private Set<String> grantedScopes() {
        Authentication authentication = SecurityContextHolder.getContextHolderStrategy().getContext()
                .getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return Set.of();
        }
        OAuth2AuthorizedClient client = this.authorizedClients.loadAuthorizedClient(this.registrationId,
                authentication.getName());
        return (client == null) ? Set.of() : client.getAccessToken().getScopes();
    }
}
