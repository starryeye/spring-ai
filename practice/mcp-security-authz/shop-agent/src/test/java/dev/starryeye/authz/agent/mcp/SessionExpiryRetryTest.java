package dev.starryeye.authz.agent.mcp;

import dev.starryeye.authz.agent.mcp.FakeStreamableMcpServer.Recorded;
import dev.starryeye.authz.agent.security.StepUpRequiredException;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * MCP Server가 session을 지운 뒤에도 사용자의 tool 호출이 성공하는지, 실제 MCP SDK client로 확인한다.
 *
 * <p>client는 자동 구성과 같게 만든다.
 * 사용자들이 함께 쓰는 client 하나에 {@link SecurityMcpTransportContextProvider},
 * {@link OAuth2TokenAttachingRequestCustomizer}, {@link StepUpAuthorizationErrorHandler}를 넣는다.
 * tool은 Spring AI의 {@code SyncMcpToolCallbackProvider}에서 받아 {@link SessionRetryingToolCallback}으로 감싼다.
 * 가짜 MCP Server의 token은 {@code token-<사용자 이름>}이다.
 */
class SessionExpiryRetryTest {

    private FakeStreamableMcpServer server;

    private McpSyncClient client;

    private SyncMcpToolCallbackProvider provider;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (this.client != null) {
            this.client.close();
        }
        if (this.server != null) {
            this.server.close();
        }
    }

    /** 사용자 이름마다 {@code token-<이름>}을 주는 manager다. */
    static OAuth2AuthorizedClientManager tokenPerUser() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("authserver")
                .clientId("authz-shop-agent")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("http://localhost:9030/oauth2/authorize")
                .tokenUri("http://localhost:9030/oauth2/token")
                .build();
        return request -> {
            String name = request.getPrincipal().getName();
            return new OAuth2AuthorizedClient(registration, name, new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER, "token-" + name, Instant.now(), Instant.now().plusSeconds(300)));
        };
    }

    /** tool 목록을 받으며 첫 {@code initialize}를 한다. 실제 채팅처럼 login한 사용자의 요청 안에서 부른다. */
    ToolCallback[] 서버에_연결한다(boolean listeningStream, Duration requestTimeout) throws IOException {
        this.server = new FakeStreamableMcpServer(listeningStream);
        this.client = McpClient.sync(HttpClientStreamableHttpTransport.builder(this.server.origin())
                        .addHttpRequestCustomizer(new OAuth2TokenAttachingRequestCustomizer(tokenPerUser(), "authserver"))
                        .authorizationErrorHandler(new StepUpAuthorizationErrorHandler())
                        .build())
                .transportContextProvider(new SecurityMcpTransportContextProvider())
                .requestTimeout(requestTimeout)
                .build();
        this.provider = SyncMcpToolCallbackProvider.builder().mcpClients(this.client).build();
        return SessionRetryingToolCallback.wrap(this.provider).getToolCallbacks();
    }

    ToolCallback[] 서버에_연결한다(boolean listeningStream) throws IOException {
        return 서버에_연결한다(listeningStream, Duration.ofSeconds(5));
    }

    static ToolCallback tool(ToolCallback[] tools, String name) {
        return Arrays.stream(tools).filter(tool -> tool.getToolDefinition().name().equals(name)).findFirst()
                .orElseThrow();
    }

    static void login(String name) {
        SecurityContextHolder.setContext(new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(name, null, List.of())));
    }

    /** {@code initialize}가 성공한 요청에는 모두 token이 있다. 다시 연 session의 token은 {@code expected}다. */
    void 다시_연_session의_token은(String expected) {
        List<Recorded> opened = this.server.initializes().stream().filter(request -> request.status() == 200).toList();
        assertThat(opened).allSatisfy(request -> assertThat(request.authorization()).isNotNull());
        assertThat(opened.getLast().authorization()).isEqualTo(expected);
    }

    @Test
    void 만료된_session의_호출은_한_번_다시_보내_성공한다() throws Exception {
        login("alice");
        ToolCallback getStock = tool(서버에_연결한다(false), "getStock");
        getStock.call("{}");
        // SDK가 뒤에서 보내는 token 없는 initialize의 401을 늦춘다. 다시 보낸 호출이 그 initialize에 합류하게 한다.
        this.server.delayTokenlessInitialize(Duration.ofMillis(300));

        this.server.expireSessions();
        login("bob");
        String result = getStock.call("{}");

        assertThat(result).contains("session-2");
        assertThat(this.server.toolCalls()).extracting(Recorded::sessionId, Recorded::status)
                .containsExactly(tuple("session-1", 200),
                        tuple("session-1", 404),
                        tuple("session-2", 200));
        다시_연_session의_token은("Bearer token-bob");
        assertThat(this.server.toolCalls().getLast().authorization()).isEqualTo("Bearer token-bob");
    }

    @Test
    void SDK의_뒤_initialize가_끝난_뒤라면_한_번만_다시_보내_성공한다() throws Exception {
        login("alice");
        서버에_연결한다(false);
        ToolCallback raw = tool(this.provider.getToolCallbacks(), "getStock");
        AtomicInteger calls = new AtomicInteger();
        // 두 번째 호출 전에 SDK가 뒤에서 보낸 token 없는 initialize가 401로 끝나기를 기다린다.
        // 그러면 다시 보낸 호출은 그 initialize에 합류하지 않고 bob의 token으로 session을 연다.
        ToolCallback getStock = new SessionRetryingToolCallback(new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return raw.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                if (calls.incrementAndGet() == 2) {
                    뒤의_initialize가_끝나기를_기다린다();
                }
                return raw.call(toolInput);
            }
        });
        getStock.call("{}");
        calls.set(0);

        this.server.expireSessions();
        login("bob");
        String result = getStock.call("{}");

        assertThat(result).contains("session-2");
        assertThat(calls).hasValue(2);
        assertThat(this.server.initializes()).extracting(Recorded::authorization, Recorded::status)
                .containsExactly(tuple("Bearer token-alice", 200), tuple(null, 401), tuple("Bearer token-bob", 200));
        assertThat(this.server.toolCalls()).extracting(Recorded::sessionId, Recorded::status)
                .containsExactly(tuple("session-1", 200), tuple("session-1", 404), tuple("session-2", 200));
    }

    /** 서버가 token 없는 initialize에 401로 답한 뒤, client가 그 실패를 정리할 시간을 넉넉히 준다. */
    void 뒤의_initialize가_끝나기를_기다린다() {
        try {
            this.server.awaitRequest(request -> request.isInitialize() && request.authorization() == null);
            Thread.sleep(100);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void listening_stream이_정상으로_닫혀도_다음_호출은_다시_보내_성공한다() throws Exception {
        login("alice");
        ToolCallback getStock = tool(서버에_연결한다(true), "getStock");
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
        다시_연_session의_token은("Bearer token-alice");
    }

    @Test
    void listening_stream이_끊겨도_client는_다시_연결하지_않고_다음_호출이_다시_보내_성공한다() throws Exception {
        login("alice");
        ToolCallback getStock = tool(서버에_연결한다(true), "getStock");
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
        다시_연_session의_token은("Bearer token-alice");
    }

    @Test
    void SDK가_사용자_없이_다시_보낸_initialize에_합류한_호출도_다시_보내_성공한다() throws Exception {
        login("alice");
        ToolCallback getStock = tool(서버에_연결한다(false), "getStock");
        getStock.call("{}");
        this.server.delayTokenlessInitialize(Duration.ofMillis(300));
        this.server.expireSessions();

        // 감싸지 않은 client로 불러 404를 받게 한다. 다시 보내는 호출이 없으니 SDK가 뒤에서 보내는 initialize만 나간다.
        assertThatThrownBy(() -> this.client.callTool(
                McpSchema.CallToolRequest.builder("getStock").arguments(Map.of()).build()))
                .hasMessage(SessionRetryingToolCallback.SESSION_TERMINATED);
        this.server.awaitRequest(request -> request.isInitialize() && request.authorization() == null);
        // 그 initialize가 401을 받기 전에 bob이 부른다. 이 호출은 그 initialize에 합류해 401로 끝난 뒤 다시 간다.
        login("bob");
        String result = getStock.call("{}");

        assertThat(result).contains("session-2");
        assertThat(this.server.initializes()).extracting(Recorded::authorization, Recorded::status)
                .containsExactly(tuple("Bearer token-alice", 200), tuple(null, 401), tuple("Bearer token-bob", 200));
        assertThat(this.server.toolCalls()).extracting(Recorded::sessionId, Recorded::status)
                .containsExactly(tuple("session-1", 200), tuple("session-1", 404), tuple("session-2", 200));
    }

    @Test
    void session_없음이_아닌_실패는_다시_보내지_않는다() throws Exception {
        login("alice");
        ToolCallback[] tools = 서버에_연결한다(false, Duration.ofMillis(500));

        assertThatThrownBy(() -> tool(tools, "brokenTool").call("{}")).isInstanceOf(ToolExecutionException.class);
        assertThatThrownBy(() -> tool(tools, "slowTool").call("{}")).isInstanceOf(ToolExecutionException.class);

        assertThat(this.server.toolCalls()).extracting(Recorded::status).containsExactly(200, 200);
    }

    @Test
    void step_up_예외는_다시_보내지_않고_그대로_올린다() throws Exception {
        login("alice");
        ToolCallback updateStock = tool(서버에_연결한다(false), "updateStock");

        assertThatThrownBy(() -> updateStock.call("{}"))
                .satisfies(error -> assertThat(StepUpRequiredException.find(error)).get()
                        .extracting(StepUpRequiredException::scopes).isEqualTo(List.of("products:write")));

        assertThat(this.server.toolCalls()).extracting(Recorded::status).containsExactly(403);
    }

    @Test
    void 만료_뒤_다시_보낸_호출이_step_up을_요구하면_그대로_올린다() throws Exception {
        login("alice");
        ToolCallback[] tools = 서버에_연결한다(false);
        tool(tools, "getStock").call("{}");
        this.server.expireSessions();

        assertThatThrownBy(() -> tool(tools, "updateStock").call("{}"))
                .satisfies(error -> assertThat(StepUpRequiredException.find(error)).isPresent());

        assertThat(this.server.toolCalls()).extracting(Recorded::sessionId, Recorded::status)
                .containsExactly(tuple("session-1", 200), tuple("session-1", 404), tuple("session-2", 403));
    }
}
