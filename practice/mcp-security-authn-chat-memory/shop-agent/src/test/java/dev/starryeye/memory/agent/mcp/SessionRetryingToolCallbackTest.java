package dev.starryeye.memory.agent.mcp;

import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 어떤 실패를 다시 보내고 어떤 실패를 그대로 올리는지 확인한다.
 *
 * <p>예외는 Spring AI의 {@code SyncMcpToolCallback}이 실제로 던지는 모양으로 만든다.
 * 그 모양이 실제 SDK에서 나오는지는 {@link SessionExpiryRetryTest}가 가짜 MCP Server로 확인한다.
 */
class SessionRetryingToolCallbackTest {

    static final ToolDefinition GET_STOCK = DefaultToolDefinition.builder()
            .name("getStock")
            .description("재고를 본다")
            .inputSchema("{\"type\":\"object\"}")
            .build();

    static final URI MCP = URI.create("http://localhost:8131/mcp");

    /** SDK가 session을 닫으며 기다리던 요청을 끝낼 때 쓰는 예외다. */
    static ToolExecutionException session이_없어졌다() {
        return new ToolExecutionException(GET_STOCK, new RuntimeException("MCP session with server terminated"));
    }

    static McpHttpClientTransportAuthorizationException authorizationError(int status,
                                                                           Map<String, List<String>> headers) {
        HttpRequestSnapshot request = new HttpRequestSnapshot(MCP, "POST", HttpHeaders.of(headers, (name, value) -> true));
        return new McpHttpClientTransportAuthorizationException("Authorization error when sending message", request,
                responseInfo(status));
    }

    static HttpResponse.ResponseInfo responseInfo(int status) {
        return new HttpResponse.ResponseInfo() {

            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (name, value) -> true);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    /** 정해 둔 실패를 차례로 던지고, 실패가 다 떨어지면 답한다. 받은 입력을 모두 기록한다. */
    static class ScriptedTool implements ToolCallback {

        private final Deque<RuntimeException> failures;

        final List<String> inputs = new CopyOnWriteArrayList<>();

        final List<ToolContext> contexts = new CopyOnWriteArrayList<>();

        ScriptedTool(RuntimeException... failures) {
            this.failures = new ArrayDeque<>(List.of(failures));
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return GET_STOCK;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            this.inputs.add(toolInput);
            this.contexts.add(toolContext);
            RuntimeException failure = this.failures.poll();
            if (failure != null) {
                throw failure;
            }
            return "[{\"text\":\"p1: 3개\"}]";
        }
    }

    @Test
    void session이_없어져_끝난_호출은_같은_입력으로_한_번_다시_보낸다() {
        ScriptedTool tool = new ScriptedTool(session이_없어졌다());
        ToolContext context = new ToolContext(Map.of("conversation", "c-1"));

        String result = new SessionRetryingToolCallback(tool).call("{\"productId\":\"p1\"}", context);

        assertThat(result).isEqualTo("[{\"text\":\"p1: 3개\"}]");
        assertThat(tool.inputs).containsExactly("{\"productId\":\"p1\"}", "{\"productId\":\"p1\"}");
        assertThat(tool.contexts).containsExactly(context, context);
    }

    @Test
    void 원인_사슬에_session_없음_예외가_있어도_다시_보낸다() {
        ScriptedTool tool = new ScriptedTool(new ToolExecutionException(GET_STOCK,
                new RuntimeException("Client failed to initialize calling tool",
                        new McpTransportSessionNotFoundException("session-1"))));

        new SessionRetryingToolCallback(tool).call("{}");

        assertThat(tool.inputs).hasSize(2);
    }

    @Test
    void 다시_보낸_호출도_session이_없어져_끝나면_더_보내지_않고_그_실패를_올린다() {
        ToolExecutionException second = session이_없어졌다();
        ScriptedTool tool = new ScriptedTool(session이_없어졌다(), second);

        assertThatThrownBy(() -> new SessionRetryingToolCallback(tool).call("{}")).isSameAs(second);
        assertThat(tool.inputs).hasSize(2);
    }

    static Stream<Arguments> session_없음이_아닌_실패() {
        return Stream.of(
                Arguments.of("응답 제한 시간", new ToolExecutionException(GET_STOCK,
                        new RuntimeException(new TimeoutException("Did not observe any item within 20000ms")))),
                Arguments.of("연결 오류", new ToolExecutionException(GET_STOCK,
                        new RuntimeException(new ConnectException("Connection refused")))),
                Arguments.of("isError 결과", new ToolExecutionException(GET_STOCK,
                        new IllegalStateException("Error calling tool: [TextContent[text=재고 저장소에 닿지 못했다]]"))),
                Arguments.of("JSON-RPC 오류", McpError.builder(-32603).message("Internal error").build()),
                Arguments.of("403", new ToolExecutionException(GET_STOCK,
                        authorizationError(403, Map.of("Authorization", List.of("Bearer token-alice"))))),
                Arguments.of("initialize의 401", new ToolExecutionException(GET_STOCK,
                        new RuntimeException("Client failed to initialize calling tool",
                                authorizationError(401, Map.of("Authorization", List.of("Bearer token-alice")))))),
                Arguments.of("token 없이 나간 initialize의 401", new ToolExecutionException(GET_STOCK,
                        new RuntimeException("Client failed to initialize calling tool", authorizationError(401, Map.of())))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("session_없음이_아닌_실패")
    void session_없음이_아닌_실패는_다시_보내지_않는다(String name, RuntimeException failure) {
        ScriptedTool tool = new ScriptedTool(failure);

        assertThatThrownBy(() -> new SessionRetryingToolCallback(tool).call("{}")).isSameAs(failure);
        assertThat(tool.inputs).hasSize(1);
    }

    @Test
    void 원인_사슬이_고리여도_멈춘다() {
        RuntimeException first = new RuntimeException("a");
        RuntimeException second = new RuntimeException("b", first);
        first.initCause(second);
        ScriptedTool tool = new ScriptedTool(first);

        assertThatThrownBy(() -> new SessionRetryingToolCallback(tool).call("{}")).isSameAs(first);
        assertThat(tool.inputs).hasSize(1);
    }

    @Test
    void tool_정의와_metadata는_감싼_callback의_것이다() {
        ToolCallback returnsDirectly = new ScriptedTool() {

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(true).build();
            }
        };

        SessionRetryingToolCallback callback = new SessionRetryingToolCallback(returnsDirectly);

        assertThat(callback.getToolDefinition()).isSameAs(GET_STOCK);
        assertThat(callback.getToolMetadata().returnDirect()).isTrue();
    }

    @Test
    void wrap은_tool마다_씌우고_tool_목록은_부를_때마다_provider에서_받는다() {
        AtomicInteger listed = new AtomicInteger();
        ScriptedTool tool = new ScriptedTool(session이_없어졌다());
        ToolCallbackProvider provider = () -> {
            listed.incrementAndGet();
            return new ToolCallback[] { tool };
        };

        ToolCallbackProvider wrapped = SessionRetryingToolCallback.wrap(provider);
        assertThat(listed).hasValue(0);

        ToolCallback[] callbacks = wrapped.getToolCallbacks();
        assertThat(listed).hasValue(1);
        assertThat(callbacks).singleElement().isInstanceOf(SessionRetryingToolCallback.class);
        assertThat(callbacks[0].getToolDefinition()).isSameAs(GET_STOCK);

        callbacks[0].call("{}");
        assertThat(tool.inputs).hasSize(2);
        wrapped.getToolCallbacks();
        assertThat(listed).hasValue(2);
    }
}
