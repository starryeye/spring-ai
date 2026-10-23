package dev.starryeye.memory.agent.mcp;

import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * MCP Server가 session을 지워 실패한 tool 호출을 한 번 다시 보낸다.
 *
 * <p>MCP Server는 한동안 쓰지 않은 session을 지운다(이 practice는 1시간).
 * 그 뒤 그 session ID로 온 요청에는 {@code 404}로 답한다.
 * 명세는 client가 {@code 404}를 받으면 새 session을 시작하라고 한다.
 * MCP Java SDK 2.1.0은 session을 버리고 뒤에서 {@code initialize}를 다시 보내지만, 실패한 그 호출은 다시 보내지 않는다.
 * 그대로 두면 오래 쉰 뒤 첫 질문의 tool 호출이 오류로 끝난다.
 * 그래서 이 callback이 같은 입력으로 한 번 더 부른다.
 *
 * <p>SDK가 뒤에서 보내는 {@code initialize}에도 client 주인의 token이 붙는다({@link OAuth2TokenAttachingRequestCustomizer}).
 * 다시 보낸 호출은 그 {@code initialize}에 합류하거나 스스로 {@code initialize}해, 같은 사용자의 새 session으로 나간다.
 * 그래서 사용자들이 client 하나를 함께 쓰는 official practice와 달리 {@code 401}을 따로 다루지 않는다.
 *
 * <p>다시 보내는 실패는 session이 없어진 것 하나다.
 * 이때 MCP Server는 {@code 404}로 거절했으므로 tool을 실행하지 않았다.
 * SDK는 {@code 404}를 받으면 그 session에서 기다리던 요청을 모두 {@value #SESSION_TERMINATED} 예외로 끝낸다.
 * 그래서 호출한 쪽에는 {@link McpTransportSessionNotFoundException}보다 이 예외가 먼저 닿는다.
 * 둘 중 하나가 원인 사슬에 있으면 session이 없어진 것으로 본다.
 *
 * <p>timeout, 연결 오류, {@code isError} 결과, {@code 401}·{@code 403}은 다시 보내지 않는다.
 * MCP Server가 이미 tool을 실행했을 수 있거나, 다시 보내도 같은 답이 오기 때문이다.
 * 이미 실행된 쓰기 tool을 다시 보내면 같은 변경이 두 번 일어난다.
 *
 * <p>막지 못하는 경우가 하나 있다.
 * SDK는 {@code 404}가 지금 session의 것인지 보지 않고 지금 session을 버린다.
 * 같은 사용자가 만료 직후 두 채팅에서 동시에 부르면, 늦게 온 옛 session의 {@code 404}가 새 session을 닫을 수 있다.
 * 그때 새 session에서 이미 실행된 호출도 session이 없어진 것처럼 끝나 다시 보내질 수 있다.
 */
public class SessionRetryingToolCallback implements ToolCallback {

    /** SDK의 {@code McpClientSession}이 session을 닫으며 기다리던 요청을 끝낼 때 쓰는 문구다(SDK 2.1.0). */
    static final String SESSION_TERMINATED = "MCP session with server terminated";

    private static final Logger log = LoggerFactory.getLogger(SessionRetryingToolCallback.class);

    private final ToolCallback delegate;

    public SessionRetryingToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    /**
     * provider의 tool마다 이 callback을 씌운다.
     * tool 목록은 부를 때마다 provider에서 받는다.
     * provider가 tool 목록을 처음 받는 때를 바꾸지 않기 위해서다.
     */
    public static ToolCallbackProvider wrap(ToolCallbackProvider provider) {
        return () -> Arrays.stream(provider.getToolCallbacks())
                .map(SessionRetryingToolCallback::new)
                .toArray(ToolCallback[]::new);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return this.delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return this.delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        try {
            return this.delegate.call(toolInput, toolContext);
        }
        catch (RuntimeException ex) {
            if (!sessionEnded(ex)) {
                throw ex;
            }
            log.info("MCP session이 없어 같은 tool 호출을 다시 보낸다 (tool={})", this.delegate.getToolDefinition().name());
        }
        return this.delegate.call(toolInput, toolContext);
    }

    /** {@code initCause}로 A → B → A 같은 고리가 생길 수 있다. 한 번 본 예외를 다시 만나면 멈춘다. */
    static boolean sessionEnded(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof McpTransportSessionNotFoundException
                    || (cause.getClass() == RuntimeException.class && SESSION_TERMINATED.equals(cause.getMessage()))) {
                return true;
            }
        }
        return false;
    }
}
