package dev.starryeye.visibility.agent.security;

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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 인증된 사용자가 보내는 authorization request에는 지금 token의 scope를 늘 더한다.
 * {@code step_up} parameter가 있으면 요청한 scope도 더한다(안내서 10장).
 *
 * <p>MCP 2026-07-28은 client가 이전 scope와 새 scope를 합쳐 요청하게 한다. scope는 쌓여 간다.
 * 새 scope만 요청하면 새 token에서 조회 권한이 빠진다.
 * step-up이 아닌 login도 마찬가지다.
 * 인증된 채 {@code /oauth2/authorization/authserver}로 다시 login하면 원래 요청에는 discovery가 고른 scope만 있다.
 * 그대로 보내면 step-up으로 받은 scope가 조용히 빠진다.
 * 처음 login(아직 인증 전)에는 더할 scope가 없으므로 원래 요청을 그대로 쓴다.
 *
 * <p>이 resolver는 {@code OAuth2AuthorizationRequestRedirectFilter} 안에서 도는데, 그 filter는
 * {@code SecurityContextHolderFilter}(session에서 인증 정보를 꺼내는 filter) <b>다음</b>에 있다.
 * 그래서 {@link HttpServletRequest#getUserPrincipal()}이 아니라
 * {@link SecurityContextHolder}에서 인증 정보를 읽어야 한다. {@code getUserPrincipal()}은 이 자리에서
 * 늘 {@code null}이다({@code SecurityContextHolderAwareRequestFilter}가 그보다 뒤에 있다).
 *
 * <p>{@code step_up} 값은 MCP Server가 실제로 {@code 403 insufficient_scope}로 요구한
 * scope({@link StepUpState#challenge})로만 좁힌다. 그렇지 않으면 다른 사이트가 이 GET
 * endpoint를 {@code <img>} 같은 subresource로 불러 임의의 scope로 step-up을 시작시킬 수 있다.
 * 같은 이유로 {@code step_up} 값을 로그에 그대로 남기지 않는다({@code forLog}).
 */
public class StepUpAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    public static final String PARAMETER = "step_up";

    private static final Logger log = LoggerFactory.getLogger(StepUpAuthorizationRequestResolver.class);

    /** RFC 6749 3.3의 scope-token 문자(NQCHAR)가 아닌 것이다. */
    private static final Pattern NOT_SCOPE_CHARACTER = Pattern.compile("[^\\x21\\x23-\\x5B\\x5D-\\x7E]");

    private static final int LOG_TOKEN_LENGTH = 64;

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
        return accumulate(request, this.delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return accumulate(request, this.delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest accumulate(HttpServletRequest request, OAuth2AuthorizationRequest original) {
        if (original == null) {
            return null;
        }
        Set<String> scopes = new LinkedHashSet<>(original.getScopes());
        scopes.addAll(grantedScopes());
        List<String> added = stepUpScopes(request);
        if (!added.isEmpty()) {
            scopes.addAll(added);
            log.info("step-up authorization request — 추가 scope={}, 요청 scope={}", added, scopes);
        }
        if (scopes.equals(original.getScopes())) {
            return original;
        }
        // from(...)은 이미 만든 주소를 복사하지 않는다. build()가 바뀐 scope로 주소를 다시 만든다.
        return OAuth2AuthorizationRequest.from(original).scopes(scopes).build();
    }

    /**
     * {@code step_up} 값 가운데 MCP Server가 요구한 scope만 돌려주고, 그 scope로 step-up 기다림을 시작한다.
     * 받아 줄 scope가 없으면 빈 목록이다.
     */
    private List<String> stepUpScopes(HttpServletRequest request) {
        String requested = request.getParameter(PARAMETER);
        if (requested == null || requested.isBlank()) {
            return List.of();
        }
        List<String> tokens = List.of(requested.trim().split("\\s+"));
        StepUpState state = StepUpState.existing(request.getSession(false));
        if (state == null) {
            log.warn("step-up 상태가 없어(session 만료 등) step-up 없이 login한다 — step_up={}", forLog(tokens));
            return List.of();
        }
        List<String> challenged = tokens.stream().filter(state::challenged).distinct().toList();
        if (challenged.isEmpty()) {
            log.warn("MCP Server가 요구한 적 없는 scope라 무시한다 — step_up={}", forLog(tokens));
            return List.of();
        }
        state.start(challenged);
        return challenged;
    }

    /**
     * {@code step_up} 값은 다른 사이트가 넣었을 수 있다(CWE-117 log injection).
     * 공백으로 나눈 값만 받으므로 CR/LF는 이미 빠져 있다.
     * ESC 같은 제어 문자는 남을 수 있어, scope에 쓸 수 없는 문자는 {@code ?}로 바꾼다.
     * 값 하나는 64자까지만 남긴다.
     */
    private static List<String> forLog(List<String> tokens) {
        return tokens.stream()
                .map(token -> NOT_SCOPE_CHARACTER.matcher(token).replaceAll("?"))
                .map(token -> (token.length() > LOG_TOKEN_LENGTH) ? token.substring(0, LOG_TOKEN_LENGTH) + "..." : token)
                .toList();
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
