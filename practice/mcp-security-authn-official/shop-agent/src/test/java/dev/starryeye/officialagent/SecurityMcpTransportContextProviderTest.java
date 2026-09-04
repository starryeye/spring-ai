package dev.starryeye.officialagent;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
    void 인증이_있으면_컨텍스트에_담는다() {
        var authentication = new UsernamePasswordAuthenticationToken("user", "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        McpTransportContext context = provider.get();

        assertThat(context).isNotEqualTo(McpTransportContext.EMPTY);
        assertThat(context.get(SecurityMcpTransportContextProvider.AUTHENTICATION_KEY))
                .isSameAs(authentication);
    }
}
