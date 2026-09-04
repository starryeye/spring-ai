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
 * Spring Security 와 MCP 전송 계층 사이의 다리.
 *
 * <p>MCP SDK 는 HTTP 요청을 보내기 직전에 이 {@link Supplier} 를 호출해
 * {@link McpTransportContext} 를 받아간다. 우리는 거기에 현재 인증을 담아
 * {@link OAuth2TokenAttachingRequestCustomizer} 가 꺼내 쓸 수 있게 한다.
 *
 * <p>community 버전에서 {@code AuthenticationMcpTransportContextProvider} 가
 * 하던 일이다. 그쪽은 {@code Authentication} 과 함께
 * {@code RequestAttributes}(서블릿 요청)도 담았는데, 이 practice 는
 * {@code AuthorizedClientServiceOAuth2AuthorizedClientManager} 를 쓰므로
 * 서블릿 요청이 필요 없다 — {@code Authentication} 하나면 된다.
 */
public class SecurityMcpTransportContextProvider implements Supplier<McpTransportContext> {

    /** 컨텍스트에 인증을 담을 때 쓰는 키. 커스터마이저가 같은 키로 꺼낸다. */
    public static final String AUTHENTICATION_KEY = Authentication.class.getName();

    private static final Logger log = LoggerFactory.getLogger(SecurityMcpTransportContextProvider.class);

    @Override
    public McpTransportContext get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // AnonymousAuthenticationToken.isAuthenticated() 는 true 다 — 익명 사용자도
        // "인증됨"으로 취급되는 Spring Security 의 일반적인 동작이다. 이 검사가 없으면
        // 익명 컨텍스트가 그대로 담겨 다음 단계(OAuth2TokenAttachingRequestCustomizer)에서
        // "인가된 클라이언트를 찾지 못했다"로 실패한다 — 원인이 "인증이 아예 없었다"는
        // 사실과 다르게 보여, 트러블슈팅 표가 엉뚱한 절반을 가리키게 된다.
        boolean noRealAuthentication = authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;

        if (noRealAuthentication) {
            // 스트리밍 경로에서 컨텍스트 전파가 안 되면 여기로 떨어진다.
            // 그러면 토큰이 붙지 않고 MCP 서버가 401 을 준다.
            log.debug("인증 없음 — 빈 전송 컨텍스트를 만든다 (토큰이 붙지 않는다)");
            return McpTransportContext.EMPTY;
        }

        log.debug("전송 컨텍스트에 인증을 담는다: {}", authentication.getName());
        return McpTransportContext.create(Map.of(AUTHENTICATION_KEY, authentication));
    }
}
