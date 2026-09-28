package dev.starryeye.authz.agent.mcp;

import dev.starryeye.authz.agent.security.BearerChallenge;
import dev.starryeye.authz.agent.security.StepUpRequiredException;

import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientTransportAuthorizationErrorHandler;
import io.modelcontextprotocol.common.McpTransportContext;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.net.http.HttpResponse;

/**
 * MCP 요청이 {@code 403 insufficient_scope}를 받으면 {@link StepUpRequiredException}으로 올린다.
 *
 * <p>SDK는 {@code 401}·{@code 403}을 받으면 이 handler를 부른다.
 * {@code true}를 돌려주면 같은 요청을 다시 보내고, 오류를 돌려주면 그 오류를 호출한 쪽에 그대로 전한다.
 * 웹 agent는 여기서 새 token을 받을 수 없다. 사용자의 browser가 consent 화면을 거쳐야 하기 때문이다.
 * 그래서 다시 보내지 않고, 예외로 채팅까지 올린다.
 */
public class StepUpAuthorizationErrorHandler implements McpHttpClientTransportAuthorizationErrorHandler {

    @Override
    public Publisher<Boolean> handle(HttpRequestSnapshot requestSnapshot, HttpResponse.ResponseInfo responseInfo,
            McpTransportContext context) {
        if (responseInfo.statusCode() != 403) {
            return Mono.just(false);
        }
        return responseInfo.headers().firstValue("WWW-Authenticate")
                .flatMap(BearerChallenge::parse)
                .filter(BearerChallenge::insufficientScope)
                .<Publisher<Boolean>>map(challenge -> Mono.error(new StepUpRequiredException(challenge.scopes(), null)))
                .orElseGet(() -> Mono.just(false));
    }
}
