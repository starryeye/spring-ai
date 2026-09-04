package dev.starryeye.officialagent;

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
        // AnonymousAuthenticationToken.isAuthenticated() 는 true 이므로, 이 검사가
        // 없으면 익명 사용자도 "인증됨"으로 통과해 버린다 — M2 로 잡은 회귀.
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
