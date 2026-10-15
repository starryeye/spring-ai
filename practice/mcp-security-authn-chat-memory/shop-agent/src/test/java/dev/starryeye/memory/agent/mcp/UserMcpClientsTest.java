package dev.starryeye.memory.agent.mcp;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    /** 빌렸다가 바로 돌려준다. 진행 중인 채팅이 없는 상태를 만든다. */
    ToolCallbackProvider 빌렸다_돌려준다(UserMcpClients clients, String name) {
        try (UserMcpClients.Lease lease = clients.acquire(user(name))) {
            return lease.tools();
        }
    }

    @Test
    void 사용자마다_다른_client_를_열고_initialize_한다() {
        빌렸다_돌려준다(this.clients, "alice");
        빌렸다_돌려준다(this.clients, "bob");

        assertThat(this.opened.get("alice")).hasSize(1);
        assertThat(this.opened.get("bob")).hasSize(1);
        verify(this.opened.get("alice").get(0)).initialize();
        verify(this.opened.get("bob").get(0)).initialize();
    }

    @Test
    void 같은_사용자는_client_를_다시_쓴다() {
        ToolCallbackProvider first = 빌렸다_돌려준다(this.clients, "alice");
        ToolCallbackProvider second = 빌렸다_돌려준다(this.clients, "alice");

        assertThat(second).isSameAs(first);
        assertThat(this.opened.get("alice")).hasSize(1);
    }

    @Test
    void 같은_사용자의_동시_첫_요청도_client_를_하나만_연다() throws Exception {
        // 첫 initialize 를 붙잡아 둔 채 나머지 요청을 보내, 여러 요청이 실제로 "여는 중"과 겹치게 만든다.
        CountDownLatch initializing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger initializeCalls = new AtomicInteger();
        UserMcpClients slow = new UserMcpClients(owner -> {
            McpSyncClient client = mock(McpSyncClient.class);
            when(client.initialize()).thenAnswer(invocation -> {
                initializeCalls.incrementAndGet();
                initializing.countDown();
                release.await();
                return null;
            });
            this.opened.computeIfAbsent(owner.getName(), name -> new CopyOnWriteArrayList<>()).add(client);
            return client;
        });
        int requests = 8;
        CountDownLatch started = new CountDownLatch(requests);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<ToolCallbackProvider>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    started.countDown();
                    return 빌렸다_돌려준다(slow, "alice");
                }));
            }
            assertThat(initializing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            // 나머지 요청이 여는 중인 client 를 기다리는 자리까지 가게 잠깐 둔다.
            Thread.sleep(200);
            assertThat(results).noneMatch(Future::isDone);

            release.countDown();
            List<ToolCallbackProvider> tools = new ArrayList<>();
            for (Future<ToolCallbackProvider> result : results) {
                tools.add(result.get(5, TimeUnit.SECONDS));
            }

            assertThat(this.opened.get("alice")).hasSize(1);
            assertThat(initializeCalls).hasValue(1);
            assertThat(tools).allMatch(provider -> provider == tools.get(0));
        }
        finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 다른_사용자의_첫_요청은_앞_사용자의_initialize_를_기다리지_않는다() throws Exception {
        // "Aa"와 "BB"는 String#hashCode가 같아 ConcurrentHashMap의 같은 bin에 들어간다.
        // map 갱신 안에서 initialize(network)를 하면 Aa의 initialize가 끝날 때까지 BB도 막힌다.
        CountDownLatch initializing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UserMcpClients slow = new UserMcpClients(owner -> {
            McpSyncClient client = mock(McpSyncClient.class);
            if ("Aa".equals(owner.getName())) {
                when(client.initialize()).thenAnswer(invocation -> {
                    initializing.countDown();
                    release.await();
                    return null;
                });
            }
            return client;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> aa = pool.submit(() -> 빌렸다_돌려준다(slow, "Aa"));
            assertThat(initializing.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> bb = pool.submit(() -> 빌렸다_돌려준다(slow, "BB"));
            assertThatCode(() -> bb.get(2, TimeUnit.SECONDS)).doesNotThrowAnyException();

            release.countDown();
            aa.get(5, TimeUnit.SECONDS);
        }
        finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 닫으면_session_을_끝내고_다음_요청은_새_client_를_연다() {
        빌렸다_돌려준다(this.clients, "alice");

        this.clients.close("alice");
        빌렸다_돌려준다(this.clients, "alice");

        verify(this.opened.get("alice").get(0)).closeGracefully();
        assertThat(this.opened.get("alice")).hasSize(2);
    }

    @Test
    void 채팅이_빌려_쓰는_중에_닫으면_돌려줄_때_닫는다() {
        // 다른 탭의 logout 이 진행 중인 채팅의 client 를 바로 닫으면, 그 채팅의 다음 tools/call 이 닫힌 transport 로 나간다.
        UserMcpClients.Lease chatting = this.clients.acquire(user("alice"));
        McpSyncClient client = this.opened.get("alice").get(0);

        this.clients.close("alice");
        verify(client, never()).closeGracefully();

        chatting.close();
        verify(client).closeGracefully();
    }

    @Test
    void 닫은_뒤의_요청은_진행_중인_채팅과_상관없이_새_client_를_연다() {
        UserMcpClients.Lease chatting = this.clients.acquire(user("alice"));
        this.clients.close("alice");

        try (UserMcpClients.Lease next = this.clients.acquire(user("alice"))) {
            assertThat(next.tools()).isNotSameAs(chatting.tools());
        }
        chatting.close();

        assertThat(this.opened.get("alice")).hasSize(2);
        verify(this.opened.get("alice").get(0)).closeGracefully();
        verify(this.opened.get("alice").get(1), never()).closeGracefully();
    }

    @Test
    void 여러_채팅이_빌려_쓰면_마지막이_돌려줄_때_한_번_닫는다() {
        UserMcpClients.Lease first = this.clients.acquire(user("alice"));
        UserMcpClients.Lease second = this.clients.acquire(user("alice"));
        McpSyncClient client = this.opened.get("alice").get(0);

        this.clients.close("alice");
        first.close();
        first.close();
        verify(client, never()).closeGracefully();

        second.close();
        verify(client, times(1)).closeGracefully();
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

        assertThatThrownBy(() -> flaky.acquire(user("alice"))).isInstanceOf(IllegalStateException.class);
        빌렸다_돌려준다(flaky, "alice");

        verify(created.get(0)).closeGracefully();
        assertThat(created).hasSize(2);
    }

    @Test
    void HTTP_session_이_끝나면_그_session_사용자의_client_를_닫는다() {
        빌렸다_돌려준다(this.clients, "alice");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(user("alice")));

        this.clients.onSessionDestroyed(new HttpSessionDestroyedEvent(session));

        verify(this.opened.get("alice").get(0)).closeGracefully();
    }

    @Test
    void 종료할_때_모든_client_를_닫는다() {
        빌렸다_돌려준다(this.clients, "alice");
        빌렸다_돌려준다(this.clients, "bob");

        this.clients.destroy();

        verify(this.opened.get("alice").get(0)).closeGracefully();
        verify(this.opened.get("bob").get(0)).closeGracefully();
    }
}
