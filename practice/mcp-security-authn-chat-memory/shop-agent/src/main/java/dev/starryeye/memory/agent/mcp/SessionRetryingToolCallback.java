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
import java.util.function.Predicate;

/**
 * MCP Server가 session을 지워 실패한 tool 호출을 다시 보낸다.
 *
 * <p>MCP Server는 한동안 쓰지 않은 session을 지운다(이 practice는 1시간).
 * 그 뒤 그 session ID로 온 요청에는 {@code 404}로 답한다.
 * 명세는 client가 {@code 404}를 받으면 새 session을 시작하라고 한다.
 * MCP Java SDK 2.1.0은 session을 버리고 뒤에서 {@code initialize}를 다시 보내지만, 실패한 그 호출은 다시 보내지 않는다.
 * 그대로 두면 오래 쉰 뒤 첫 질문의 tool 호출이 오류로 끝난다.
 * 그래서 이 callback이 같은 입력으로 한 번 더 부른다.
 *
 * <p>다시 보내는 실패는 session이 없어진 것이다.
 * SDK는 session ID를 붙인 요청이 {@code 404}나 {@code 400}을 받으면 session이 없어진 것으로 본다.
 * 그러면 그 client에서 기다리던 호출을 모두 {@value #SESSION_TERMINATED} 예외로 끝낸다.
 * 그래서 호출한 쪽에는 {@link McpTransportSessionNotFoundException}보다 이 예외가 먼저 닿는다.
 * 다시 보낸 호출이 {@code initialize} 단계에서 끝났으면(그 사이 session이 또 없어졌다) 한 번 더 부른다.
 * 그래서 delegate는 세 번까지 부른다.
 *
 * <p>SDK가 뒤에서 보내는 {@code initialize}에도 client 주인의 token이 붙는다({@link OAuth2TokenAttachingRequestCustomizer}).
 * 다시 보낸 호출은 그 {@code initialize}에 합류하거나 스스로 {@code initialize}해, 같은 사용자의 새 session으로 나간다.
 * 그래서 사용자들이 client 하나를 함께 쓰는 official practice와 달리 {@code 401}을 따로 다루지 않는다.
 *
 * <p>MCP Server가 tool을 실행하지 않은 것이 확실한 실패는 둘이다.
 * 원인 사슬에 {@link McpTransportSessionNotFoundException}이 있으면 이 호출 자신이 거절되었다.
 * {@code initialize} 단계에서 끝났으면 tool 요청은 아직 나가지 않았다.
 * {@value #SESSION_TERMINATED} 예외만으로는 확실하지 않다.
 * 그 {@code 404}가 이 호출이 쓴 session의 것일 때만 이 호출이 실행되지 않았다고 말할 수 있다.
 * SDK 2.1.0은 이를 가리지 않는다.
 * {@code 404}의 session ID를 지금 session과 맞춰 보지 않고 지금 session을 버리고, 기다리던 호출을 모두 끝낸다.
 *
 * <p>그래서 같은 tool 호출이 두 번 실행될 수 있다.
 * 아래가 모두 겹칠 때다.
 * <ul>
 * <li>옛 session으로 나간 다른 호출의 {@code 404}가 늦게 와서, 다시 연 새 session을 닫는다.
 * 그 {@code 404}는 옛 session의 {@code DELETE}가 끝나기 전에 와야 한다.
 * {@code DefaultMcpTransportSession.closeGracefully}는 {@code DELETE}가 끝난 뒤에야 옛 session의 열린 요청을 끊기 때문이다.
 * <li>그때 새 session에서 어떤 호출의 첫 시도가 이미 실행되었다.
 * 이렇게 되려면 한 사용자의 호출 셋이 동시에 있어야 한다(채팅 셋).
 * 호출 둘만으로는 다시 보낸 호출만 끝나고, 다시 보낸 호출의 session 없음은 그대로 올린다.
 * </ul>
 * 이 practice의 tool은 조회뿐이라 다시 실행해도 상태가 같다.
 * 그래서 이 위험을 받아들인다.
 *
 * <p>timeout, 연결 오류, {@code isError} 결과는 다시 보내지 않는다.
 * MCP Server가 이미 tool을 실행했을 수 있어서다.
 * {@code 401}·{@code 403}도 다시 보내지 않는다.
 * tool이 돌기 전에 거절된 것이지만, 다시 보내도 같은 답이 오기 때문이다.
 */
public class SessionRetryingToolCallback implements ToolCallback {

    /** SDK의 {@code McpClientSession}이 session을 닫으며 기다리던 요청을 끝낼 때 쓰는 문구다(SDK 2.1.0). */
    static final String SESSION_TERMINATED = "MCP session with server terminated";

    /** SDK의 {@code LifecycleInitializer}가 {@code initialize} 실패를 감쌀 때 쓰는 문구의 앞부분이다(SDK 2.1.0). */
    static final String INITIALIZATION_FAILED = "Client failed to initialize ";

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
            log.info("MCP session이 없어 같은 tool 호출을 다시 보낸다 (tool={})", name());
        }
        try {
            return this.delegate.call(toolInput, toolContext);
        }
        catch (RuntimeException ex) {
            if (!initializationInterrupted(ex)) {
                throw ex;
            }
            log.info("다시 보낸 호출이 initialize 단계에서 끝났다. tool 요청은 나가지 않았으므로 한 번 더 부른다 (tool={})", name());
        }
        return this.delegate.call(toolInput, toolContext);
    }

    private String name() {
        return this.delegate.getToolDefinition().name();
    }

    static boolean sessionEnded(Throwable failure) {
        return anyCause(failure, cause -> cause instanceof McpTransportSessionNotFoundException
                || (cause.getClass() == RuntimeException.class && SESSION_TERMINATED.equals(cause.getMessage())));
    }

    /**
     * {@code initialize}가 끝나기 전에 session이 또 없어졌다.
     * SDK는 {@code initialize}가 성공한 뒤에야 tool 요청을 보내므로, 이 실패면 tool 요청은 나가지 않았다.
     */
    static boolean initializationInterrupted(Throwable failure) {
        return anyCause(failure, cause -> cause.getClass() == RuntimeException.class
                && cause.getMessage() != null && cause.getMessage().startsWith(INITIALIZATION_FAILED)
                && cause.getCause() != null && sessionEnded(cause.getCause()));
    }

    /**
     * {@code initCause}로 A → B → A 같은 고리가 생길 수 있다.
     * 한 번 본 예외를 다시 만나면 멈춘다.
     */
    private static boolean anyCause(Throwable failure, Predicate<Throwable> condition) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (condition.test(cause)) {
                return true;
            }
        }
        return false;
    }
}
