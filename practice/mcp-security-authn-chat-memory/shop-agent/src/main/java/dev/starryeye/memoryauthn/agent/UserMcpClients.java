package dev.starryeye.memoryauthn.agent;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionDestroyedEvent;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 로그인한 사용자마다 MCP client 하나를 둔다.
 *
 * <p>MCP Server 는 session 을 그 session 을 연 사용자에 묶는다({@code McpSessionBindingFilter}). client 하나를
 * 모든 사용자가 나눠 쓰면 한 session 에 여러 사용자의 token 이 실려 서버가 막는다. 그래서 principal 이름마다
 * client 를 따로 만들어 첫 채팅 때 {@code initialize} 하고, 로그아웃·HTTP session 종료·애플리케이션 종료 때
 * 닫는다({@code DELETE} 로 MCP session 종료).
 *
 * <p>client 는 만들 때 받은 {@link Authentication}(주인)의 token 만 싣는다({@link OAuth2TokenAttachingRequestCustomizer}).
 */
public class UserMcpClients implements DisposableBean {

    private final Function<Authentication, McpSyncClient> clientFactory;

    private final ConcurrentMap<String, UserClient> clients = new ConcurrentHashMap<>();

    public UserMcpClients(Function<Authentication, McpSyncClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    /** 그 사용자의 MCP tool. 처음 부르면 client 를 만들고 initialize 한다. 같은 사용자의 동시 호출도 하나만 연다. */
    public ToolCallbackProvider toolsFor(Authentication user) {
        return this.clients.computeIfAbsent(user.getName(), name -> open(user)).tools();
    }

    /** 그 사용자의 client 를 닫는다. 다음 {@link #toolsFor} 는 새 client 를 연다. */
    public void close(String username) {
        UserClient client = this.clients.remove(username);
        if (client != null) {
            client.client().closeGracefully();
        }
    }

    /** HTTP session 이 끝나면(만료·로그아웃) 그 session 의 사용자 client 를 닫는다. */
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

    private record UserClient(McpSyncClient client, ToolCallbackProvider tools) {
    }
}
