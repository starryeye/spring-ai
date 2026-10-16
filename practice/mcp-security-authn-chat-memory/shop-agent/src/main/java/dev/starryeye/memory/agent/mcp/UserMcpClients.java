package dev.starryeye.memory.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionDestroyedEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * login한 사용자마다 MCP client 하나를 둔다.
 *
 * <p>MCP Server는 session을 그 session을 연 사용자에게 묶는다({@code McpSessionBindingFilter}).
 * client 하나를 모든 사용자가 나눠 쓰면, 한 session에 여러 사용자의 token이 섞여 서버가 막는다.
 * 그래서 principal 이름마다 client를 따로 만들어 첫 채팅 때 {@code initialize}한다.
 * logout, HTTP session 종료, 애플리케이션 종료 때는 client를 닫는다({@code DELETE}로 MCP session 종료).
 *
 * <p>map에는 client가 아니라 client를 여는 {@link CompletableFuture}를 넣는다.
 * map 갱신은 짧게 끝나고, {@code initialize}(network)는 그 밖에서 한다.
 * {@code computeIfAbsent} 안에서 {@code initialize}하면, 같은 bin을 쓰는 다른 사용자의 첫 채팅과
 * {@link #close}까지 그 network 왕복을 기다린다.
 * 같은 사용자의 동시 첫 요청은 같은 future를 기다리므로 client는 하나만 열린다.
 *
 * <p>채팅 하나는 client를 {@link #acquire}로 빌리고, stream이 끝나면 돌려준다.
 * {@link #close}는 client를 map에서 빼서 다음 채팅이 새 client를 열게 한다.
 * client는 빌려 간 채팅이 모두 돌려준 뒤에야 닫힌다.
 * 그래서 다른 탭에서 logout해도, 진행 중인 채팅의 다음 {@code tools/call}이 닫힌 transport로 나가지 않는다.
 *
 * <p>client는 만들 때 받은 {@link Authentication}(주인)의 token만 붙인다({@link OAuth2TokenAttachingRequestCustomizer}).
 */
public class UserMcpClients implements DisposableBean {

    private final Function<Authentication, McpSyncClient> clientFactory;

    private final ConcurrentMap<String, CompletableFuture<UserClient>> clients = new ConcurrentHashMap<>();

    public UserMcpClients(Function<Authentication, McpSyncClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * 그 사용자의 MCP client를 빌린다. 처음 부르면 client를 만들고 initialize한다.
     * 다 쓰면 {@link Lease#close()}로 돌려준다.
     */
    public Lease acquire(Authentication user) {
        while (true) {
            UserClient client = clientOf(user);
            if (client.retain()) {
                return new Lease(client);
            }
            // 찾은 뒤 빌리기 전에 close가 이 client를 retire했다. map에서도 빠졌으므로 다시 찾으면 새 client를 연다.
        }
    }

    /** 그 사용자의 client를 닫는다. 빌려 간 채팅이 있으면 모두 돌려준 뒤에 닫는다. 다음 {@link #acquire}는 새 client를 연다. */
    public void close(String username) {
        CompletableFuture<UserClient> opening = this.clients.remove(username);
        if (opening != null) {
            // 아직 여는 중이면 다 연 뒤에 retire한다. 열지 못했으면 닫을 것이 없다.
            opening.thenAccept(UserClient::retire);
        }
    }

    /** HTTP session이 끝나면(만료·logout) 그 session의 사용자 client를 닫는다. */
    @EventListener
    public void onSessionDestroyed(SessionDestroyedEvent event) {
        for (SecurityContext context : event.getSecurityContexts()) {
            Authentication authentication = context.getAuthentication();
            if (authentication != null) {
                close(authentication.getName());
            }
        }
    }

    @Override
    public void destroy() {
        List.copyOf(this.clients.keySet()).forEach(this::close);
    }

    private UserClient clientOf(Authentication user) {
        String name = user.getName();
        CompletableFuture<UserClient> created = new CompletableFuture<>();
        CompletableFuture<UserClient> existing = this.clients.putIfAbsent(name, created);
        if (existing != null) {
            return join(existing);
        }
        try {
            created.complete(open(user));
        }
        catch (Throwable ex) {
            // 실패는 기억하지 않는다. 기다리던 요청도 같은 오류를 받고, 다음 요청이 다시 연다.
            this.clients.remove(name, created);
            created.completeExceptionally(ex);
            throw ex;
        }
        return created.join();
    }

    private static UserClient join(CompletableFuture<UserClient> opening) {
        try {
            return opening.join();
        }
        catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (ex.getCause() instanceof Error cause) {
                throw cause;
            }
            throw ex;
        }
    }

    private UserClient open(Authentication owner) {
        McpSyncClient client = this.clientFactory.apply(owner);
        try {
            client.initialize();
        }
        catch (RuntimeException ex) {
            client.closeGracefully();
            throw ex;
        }
        return new UserClient(client, SyncMcpToolCallbackProvider.builder().mcpClients(client).build());
    }

    /** 채팅 하나가 빌린 client다. 한 번만 돌려준다. */
    public static final class Lease implements AutoCloseable {

        private final UserClient client;

        private final AtomicBoolean returned = new AtomicBoolean();

        private Lease(UserClient client) {
            this.client = client;
        }

        /** 빌린 client의 MCP tool이다. */
        public ToolCallbackProvider tools() {
            return this.client.tools;
        }

        @Override
        public void close() {
            if (this.returned.compareAndSet(false, true)) {
                this.client.release();
            }
        }
    }

    /**
     * client와 빌려 간 수를 담는다. retire한({@code retired}) 뒤에는 더 빌려주지 않고,
     * 빌려 간 수가 0이 되는 순간 한 번 닫는다.
     * 닫기({@code DELETE})는 network 요청이므로 lock 밖에서 한다.
     */
    private static final class UserClient {

        private final McpSyncClient client;

        private final ToolCallbackProvider tools;

        private int leases;

        private boolean retired;

        UserClient(McpSyncClient client, ToolCallbackProvider tools) {
            this.client = client;
            this.tools = tools;
        }

        synchronized boolean retain() {
            if (this.retired) {
                return false;
            }
            this.leases++;
            return true;
        }

        void release() {
            boolean last;
            synchronized (this) {
                this.leases--;
                last = this.retired && this.leases == 0;
            }
            if (last) {
                this.client.closeGracefully();
            }
        }

        void retire() {
            boolean idle;
            synchronized (this) {
                if (this.retired) {
                    return;
                }
                this.retired = true;
                idle = this.leases == 0;
            }
            if (idle) {
                this.client.closeGracefully();
            }
        }
    }
}
