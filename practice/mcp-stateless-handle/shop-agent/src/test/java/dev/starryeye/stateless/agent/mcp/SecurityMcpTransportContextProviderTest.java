package dev.starryeye.stateless.agent.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityMcpTransportContextProviderTest {

    private final SecurityMcpTransportContextProvider provider = new SecurityMcpTransportContextProvider();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증이_없으면_빈_컨텍스트를_준다() {
        assertThat(provider.get()).isEqualTo(McpTransportContext.EMPTY);
    }

    @Test
    void 익명_인증이면_빈_컨텍스트를_준다() {
        // AnonymousAuthenticationToken.isAuthenticated()는 true다.
        // 그래서 provider가 익명 인증을 따로 거르지 않으면 익명 사용자도 "인증됨"으로 보고 context에 담는다.
        // 이 테스트는 그 검사가 빠지지 않게 지킨다.
        var anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anonymous);

        assertThat(provider.get()).isEqualTo(McpTransportContext.EMPTY);
    }

    @Test
    void 인증이_있으면_컨텍스트에_담는다() {
        var authentication = new UsernamePasswordAuthenticationToken("user", "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        McpTransportContext context = provider.get();

        assertThat(context).isNotEqualTo(McpTransportContext.EMPTY);
        assertThat(context.get(SecurityMcpTransportContextProvider.AUTHENTICATION_KEY))
                .isSameAs(authentication);
    }
}
