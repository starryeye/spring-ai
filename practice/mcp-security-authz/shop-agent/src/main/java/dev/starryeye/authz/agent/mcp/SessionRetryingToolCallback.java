package dev.starryeye.authz.agent.mcp;

import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
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

import static io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

/**
 * MCP Server가 session을 지워 실패한 tool 호출을 다시 보낸다.
 *
 * <p>MCP Server는 한동안 쓰지 않은 session을 지운다(기본 30분).
 * 그 뒤 그 session ID로 온 요청에는 {@code 404}로 답한다.
 * 명세는 client가 {@code 404}를 받으면 새 session을 시작하라고 한다.
 * MCP Java SDK 2.1.0은 session을 버리고 뒤에서 {@code initialize}를 다시 보내지만, 실패한 그 호출은 다시 보내지 않는다.
 * 그대로 두면 오래 쉰 뒤 첫 질문의 tool 호출이 오류로 끝난다.
 * 그래서 이 callback이 같은 입력으로 다시 부른다.
 *
 * <p>다시 보내는 실패는 MCP Server가 tool을 실행하지 않은 것이 확실한 두 가지다.
 * <ul>
 * <li>session이 없어졌다.
 * SDK는 {@code 404}를 받으면 그 session에서 기다리던 요청을 모두 {@value #SESSION_TERMINATED} 예외로 끝낸다.
 * 그래서 호출한 쪽에는 {@link McpTransportSessionNotFoundException}보다 이 예외가 먼저 닿는다.
 * 둘 중 하나가 원인 사슬에 있으면 session이 없어진 것으로 본다.
 * <li>token 없이 나간 {@code initialize}가 {@code 401}을 받았다.
 * SDK가 뒤에서 보내는 {@code initialize}에는 요청한 사용자가 없어 token이 붙지 않는다({@link OAuth2TokenAttachingRequestCustomizer}).
 * 그 사이 같은 client로 나간 호출은 이 {@code initialize}에 합류해 같은 {@code 401}로 끝난다.
 * tool 요청은 아직 나가지 않았다.
 * 다시 부르면 그 호출이 사용자의 token으로 session을 새로 연다.
 * </ul>
 * delegate는 세 번까지 부른다.
 * 세 번째는 두 번째가 token 없는 {@code initialize}의 {@code 401}로 끝났을 때만 부른다.
 * session이 없어진 실패로는 한 번만 다시 보낸다.
 *
 * <p>timeout, 연결 오류, {@code isError} 결과, {@code 403}은 다시 보내지 않는다.
 * MCP Server가 이미 tool을 실행했을 수 있어서다.
 * 쓰기 tool이면 같은 변경이 두 번 일어난다.
 * {@code 403 insufficient_scope}가 바뀐 {@code StepUpRequiredException}도 그대로 올린다.
 * 사용자가 consent를 거쳐야 하므로 다시 보내도 같은 {@code 403}이고, 채팅까지 올라가야 consent 카드가 뜬다.
 *
 * <p>막지 못하는 경우가 하나 있다.
 * SDK는 {@code 404}가 지금 session의 것인지 보지 않고 지금 session을 버린다.
 * 그래서 만료 직후 여러 사용자가 동시에 부르면, 늦게 온 옛 session의 {@code 404}가 새 session을 닫을 수 있다.
 * 그때 새 session에서 이미 실행된 호출도 session이 없어진 것처럼 끝난다.
 * 그 호출이 처음 보낸 것이면 이 callback이 다시 보내므로, 쓰기 tool이 두 번 실행될 수 있다.
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
     * provider는 처음 부를 때 {@code initialize}하므로, 그 일을 login한 사용자의 요청 안으로 미루기 위해서다.
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
            if (!sessionEnded(ex) && !userlessInitializeRejected(ex)) {
                throw ex;
            }
            log.info("MCP session이 없어 같은 tool 호출을 다시 보낸다 (tool={})", name());
        }
        try {
            return this.delegate.call(toolInput, toolContext);
        }
        catch (RuntimeException ex) {
            if (!userlessInitializeRejected(ex)) {
                throw ex;
            }
            log.info("SDK가 사용자 없이 보낸 initialize에 합류해 401로 끝났다. tool 요청은 나가지 않았으므로 다시 부른다 (tool={})", name());
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
     * Streamable HTTP에서 session ID 없이 나가는 POST는 {@code initialize}뿐이다.
     * 사용자가 있는 요청에는 token이 붙으므로, token도 session ID도 없는 POST는 SDK가 뒤에서 보낸 {@code initialize}다.
     */
    static boolean userlessInitializeRejected(Throwable failure) {
        return anyCause(failure, cause -> cause instanceof McpHttpClientTransportAuthorizationException rejected
                && rejected.getResponseInfo().statusCode() == 401
                && isUserlessInitialize(rejected.getRequestSnapshot()));
    }

    private static boolean isUserlessInitialize(HttpRequestSnapshot request) {
        return "POST".equals(request.method())
                && request.headers().firstValue(MCP_SESSION_ID).isEmpty()
                && request.headers().firstValue(AUTHORIZATION).isEmpty();
    }

    /** {@code initCause}로 A → B → A 같은 고리가 생길 수 있다. 한 번 본 예외를 다시 만나면 멈춘다. */
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
