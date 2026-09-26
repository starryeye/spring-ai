package dev.starryeye.officialagent;

import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Spring Security와 MCP transport 계층을 잇는다.
 *
 * <p>MCP SDK는 HTTP 요청을 보내기 전에 이 {@link Supplier}를 불러 {@link McpTransportContext}를 받는다.
 * 이 클래스는 거기에 현재 인증을 담는다.
 * 그러면 {@link OAuth2TokenAttachingRequestCustomizer}가 그 인증을 꺼내 쓸 수 있다.
 *
 * <p>community practice에서는 module의 {@code AuthenticationMcpTransportContextProvider}가 같은 일을 한다.
 * 그쪽은 {@code Authentication}과 함께 {@code RequestAttributes}(servlet 요청)도 담는다.
 * 이 practice는 {@code AuthorizedClientServiceOAuth2AuthorizedClientManager}를 쓰므로 servlet 요청이 필요 없다.
 * {@code Authentication} 하나면 된다.
 */
public class SecurityMcpTransportContextProvider implements Supplier<McpTransportContext> {

    /** context에 인증을 담을 때 쓰는 key다. customizer가 같은 key로 꺼낸다. */
    public static final String AUTHENTICATION_KEY = Authentication.class.getName();

    private static final Logger log = LoggerFactory.getLogger(SecurityMcpTransportContextProvider.class);

    @Override
    public McpTransportContext get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // AnonymousAuthenticationToken.isAuthenticated()는 true다.
        // Spring Security는 익명 사용자도 "인증됨"으로 다루기 때문이다.
        // 이 검사가 없으면 익명 context가 그대로 담긴다.
        // 그러면 다음 단계인 OAuth2TokenAttachingRequestCustomizer가
        // authorized client를 찾지 못했다는 로그를 남긴다.
        // 실제 원인은 인증이 아예 없었다는 것인데, 로그는 다른 원인을 가리킨다.
        // 안내서 6장이 원인을 찾을 때 보라고 하는 아래의 "인증 없음" DEBUG 줄도 남지 않는다.
        boolean noRealAuthentication = authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;

        if (noRealAuthentication) {
            // stream 경로에서 context가 전파되지 않으면 여기로 온다.
            // 그러면 token이 붙지 않고 MCP Server가 401을 준다.
            log.debug("인증 없음 — 빈 전송 컨텍스트를 만든다 (토큰이 붙지 않는다)");
            return McpTransportContext.EMPTY;
        }

        log.debug("전송 컨텍스트에 인증을 담는다: {}", authentication.getName());
        return McpTransportContext.create(Map.of(AUTHENTICATION_KEY, authentication));
    }
}
