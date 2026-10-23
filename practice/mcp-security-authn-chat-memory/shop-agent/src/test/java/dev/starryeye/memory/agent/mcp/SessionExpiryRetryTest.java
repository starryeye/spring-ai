package dev.starryeye.memory.agent.mcp;

import dev.starryeye.memory.agent.config.McpAuthorizationProperties;
import dev.starryeye.memory.agent.config.McpSecurityConfig;
import dev.starryeye.memory.agent.mcp.FakeStreamableMcpServer.Recorded;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * MCP Server가 session을 지운 뒤에도 사용자의 tool 호출이 성공하는지, 실제 MCP SDK client로 확인한다.
 *
 * <p>client는 {@link McpSecurityConfig}의 {@link UserMcpClients} bean이 만드는 그대로다.
 * 사용자마다 client를 열고, client 주인의 token을 붙이고, tool을 {@link SessionRetryingToolCallback}으로 감싼다.
 * 가짜 MCP Server의 token은 {@code token-<사용자 이름>}이다.
 */
class SessionExpiryRetryTest {

    private FakeStreamableMcpServer server;

    private UserMcpClients clients;

    private UserMcpClients.Lease lease;

    @AfterEach
    void tearDown() {
        if (this.lease != null) {
            this.lease.close();
        }
        if (this.clients != null) {
            this.clients.destroy();
        }
        if (this.server != null) {
            this.server.close();
        }
    }

    /** 사용자 이름마다 {@code token-<이름>}을 주는 manager다. */
    static OAuth2AuthorizedClientManager tokenPerUser() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("authserver")
                .clientId("memory-agent")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("http://localhost:9020/oauth2/authorize")
                .tokenUri("http://localhost:9020/oauth2/token")
                .build();
        return request -> {
            String name = request.getPrincipal().getName();
            return new OAuth2AuthorizedClient(registration, name, new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER, "token-" + name, Instant.now(), Instant.now().plusSeconds(300)));
        };
    }

    static Authentication user(String name) {
        return UsernamePasswordAuthenticationToken.authenticated(name, null, List.of());
    }

    /** {@link McpSecurityConfig}의 bean으로 alice의 client를 열고 tool을 받는다. */
    ToolCallback[] alice가_연결한다(boolean listeningStream) throws IOException {
        this.server = new FakeStreamableMcpServer(listeningStream);
        this.clients = new McpSecurityConfig().userMcpClients(
                new McpAuthorizationProperties(this.server.origin() + "/mcp", "http://localhost:9020"),
                tokenPerUser());
        this.lease = this.clients.acquire(user("alice"));
        return this.lease.tools().getToolCallbacks();
    }

    static ToolCallback tool(ToolCallback[] tools, String name) {
        return Arrays.stream(tools).filter(tool -> tool.getToolDefinition().name().equals(name)).findFirst()
                .orElseThrow();
    }

    /** SDK가 뒤에서 다시 보낸 {@code initialize}까지 모두 client 주인의 token을 붙였다. */
    void 모든_initialize에_alice의_token이_붙었다() {
        assertThat(this.server.initializes()).extracting(Recorded::authorization, Recorded::status)
                .containsOnly(tuple("Bearer token-alice", 200));
    }

    @Test
    void 만료된_session의_호출은_한_번_다시_보내_성공한다() throws Exception {
        ToolCallback getStock = tool(alice가_연결한다(false), "getStock");
        getStock.call("{}");

        this.server.expireSessions();
        String result = getStock.call("{}");

        assertThat(result).contains("session-2");
        assertThat(this.server.toolCalls()).extracting(Recorded::sessionId, Recorded::status)
                .containsExactly(tuple("session-1", 200), tuple("session-1", 404), tuple("session-2", 200));
        assertThat(this.server.initializes()).hasSize(2);
        모든_initialize에_alice의_token이_붙었다();
    }

    @Test
    void listening_stream이_정상으로_닫혀도_다음_호출은_다시_보내_성공한다() throws Exception {
        ToolCallback getStock = tool(alice가_연결한다(true), "getStock");
        getStock.call("{}");
        this.server.awaitRequest(request -> "GET".equals(request.httpMethod()) && request.status() == 200);

        this.server.expireSessions();
        assertThat(this.server.openStreamCount()).isZero();
        String result = getStock.call("{}");
        this.server.awaitRequest(request -> "GET".equals(request.httpMethod()) && "session-2".equals(request.sessionId()));

        assertThat(result).contains("session-2");
        // SDK는 정상으로 닫힌 stream을 다시 연결하지 않는다. session이 없어진 것은 다음 tool 호출의 404로 안다.
        assertThat(this.server.requests).filteredOn(request -> "GET".equals(request.httpMethod()))
                .extracting(Recorded::sessionId, Recorded::status)
                .containsExactly(tuple("session-1", 200), tuple("session-2", 200));
        assertThat(this.server.toolCalls()).extracting(Recorded::status).containsExactly(200, 404, 200);
        모든_initialize에_alice의_token이_붙었다();
    }

    @Test
    void listening_stream이_끊겨도_client는_다시_연결하지_않고_다음_호출이_다시_보내_성공한다() throws Exception {
        ToolCallback getStock = tool(alice가_연결한다(true), "getStock");
        getStock.call("{}");
        this.server.awaitRequest(request -> "GET".equals(request.httpMethod()) && request.status() == 200);

        this.server.expireSessionsAndDropStreams();
        // SDK가 끊긴 stream을 다시 연결한다면 곧바로 한다. 그럴 시간을 준다.
        Thread.sleep(300);
        String result = getStock.call("{}");
        this.server.awaitRequest(request -> "GET".equals(request.httpMethod()) && "session-2".equals(request.sessionId()));

        assertThat(result).contains("session-2");
        // SDK 2.1.0은 끊긴 GET stream도 다시 연결하지 않는다. 옛 session ID로 GET을 보내 404를 받는 일은 없다.
        assertThat(this.server.requests).filteredOn(request -> "GET".equals(request.httpMethod()))
                .extracting(Recorded::sessionId, Recorded::status)
                .containsExactly(tuple("session-1", 200), tuple("session-2", 200));
        assertThat(this.server.toolCalls()).extracting(Recorded::status).containsExactly(200, 404, 200);
        모든_initialize에_alice의_token이_붙었다();
    }

    @Test
    void session_없음이_아닌_실패는_다시_보내지_않는다() throws Exception {
        this.server = new FakeStreamableMcpServer(false);
        // timeout을 보려고 제한 시간만 짧게 둔다. 나머지는 McpSecurityConfig의 client와 같다.
        Authentication alice = user("alice");
        this.clients = new UserMcpClients(owner -> McpClient.sync(
                        HttpClientStreamableHttpTransport.builder(this.server.origin())
                                .addHttpRequestCustomizer(new OAuth2TokenAttachingRequestCustomizer(tokenPerUser(),
                                        McpSecurityConfig.REGISTRATION_ID, owner))
                                .build())
                .requestTimeout(Duration.ofMillis(500))
                .build());
        this.lease = this.clients.acquire(alice);
        ToolCallback[] tools = this.lease.tools().getToolCallbacks();

        assertThatThrownBy(() -> tool(tools, "brokenTool").call("{}")).isInstanceOf(ToolExecutionException.class);
        assertThatThrownBy(() -> tool(tools, "slowTool").call("{}")).isInstanceOf(ToolExecutionException.class);
        assertThatThrownBy(() -> tool(tools, "updateStock").call("{}")).isInstanceOf(ToolExecutionException.class);

        assertThat(this.server.toolCalls()).extracting(Recorded::status).containsExactly(200, 200, 403);
    }
}
