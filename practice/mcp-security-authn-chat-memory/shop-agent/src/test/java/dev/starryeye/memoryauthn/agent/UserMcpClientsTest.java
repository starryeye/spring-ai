package dev.starryeye.memoryauthn.agent;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMcpClientsTest {

    private final Map<String, List<McpSyncClient>> opened = new ConcurrentHashMap<>();

    private final UserMcpClients clients = new UserMcpClients(owner -> {
        McpSyncClient client = mock(McpSyncClient.class);
        this.opened.computeIfAbsent(owner.getName(), name -> new CopyOnWriteArrayList<>()).add(client);
        return client;
    });

    static Authentication user(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", List.of());
    }

    @Test
    void 사용자마다_다른_client_를_열고_initialize_한다() {
        this.clients.toolsFor(user("alice"));
        this.clients.toolsFor(user("bob"));

        assertThat(this.opened.get("alice")).hasSize(1);
        assertThat(this.opened.get("bob")).hasSize(1);
        verify(this.opened.get("alice").get(0)).initialize();
        verify(this.opened.get("bob").get(0)).initialize();
    }

    @Test
    void 같은_사용자는_client_를_다시_쓴다() {
        ToolCallbackProvider first = this.clients.toolsFor(user("alice"));
        ToolCallbackProvider second = this.clients.toolsFor(user("alice"));

        assertThat(second).isSameAs(first);
        assertThat(this.opened.get("alice")).hasSize(1);
    }

    @Test
    void 같은_사용자의_동시_첫_요청도_client_를_하나만_연다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<ToolCallbackProvider>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> this.clients.toolsFor(user("alice"))));
            }
            for (Future<ToolCallbackProvider> result : results) {
                result.get();
            }
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(this.opened.get("alice")).hasSize(1);
    }

    @Test
    void 닫으면_session_을_끝내고_다음_요청은_새_client_를_연다() {
        this.clients.toolsFor(user("alice"));

        this.clients.close("alice");
        this.clients.toolsFor(user("alice"));

        verify(this.opened.get("alice").get(0)).closeGracefully();
        assertThat(this.opened.get("alice")).hasSize(2);
    }

    @Test
    void initialize_가_실패하면_client_를_닫고_다음_요청에서_다시_연다() {
        List<McpSyncClient> created = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        UserMcpClients flaky = new UserMcpClients(owner -> {
            McpSyncClient client = mock(McpSyncClient.class);
            if (attempts.getAndIncrement() == 0) {
                when(client.initialize()).thenThrow(new IllegalStateException("MCP Server 가 아직 뜨지 않았다"));
            }
            created.add(client);
            return client;
        });

        assertThatThrownBy(() -> flaky.toolsFor(user("alice"))).isInstanceOf(IllegalStateException.class);
        flaky.toolsFor(user("alice"));

        verify(created.get(0)).closeGracefully();
        assertThat(created).hasSize(2);
    }

    @Test
    void HTTP_session_이_끝나면_그_session_사용자의_client_를_닫는다() {
        this.clients.toolsFor(user("alice"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(user("alice")));

        this.clients.onSessionDestroyed(new HttpSessionDestroyedEvent(session));

        verify(this.opened.get("alice").get(0)).closeGracefully();
    }

    @Test
    void 종료할_때_모든_client_를_닫는다() {
        this.clients.toolsFor(user("alice"));
        this.clients.toolsFor(user("bob"));

        this.clients.destroy();

        verify(this.opened.get("alice").get(0)).closeGracefully();
        verify(this.opened.get("bob").get(0)).closeGracefully();
    }
}
