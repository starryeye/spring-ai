package dev.starryeye.cimd.agent.mcp;

import dev.starryeye.cimd.agent.security.StepUpRequiredException;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepUpAuthorizationErrorHandlerTest {

    record Info(int statusCode, HttpHeaders headers, HttpClient.Version version) implements HttpResponse.ResponseInfo {
    }

    static HttpResponse.ResponseInfo info(int status, String challenge) {
        Map<String, List<String>> headers = (challenge == null) ? Map.of() : Map.of("WWW-Authenticate", List.of(challenge));
        return new Info(status, HttpHeaders.of(headers, (name, value) -> true), HttpClient.Version.HTTP_1_1);
    }

    StepUpAuthorizationErrorHandler handler = new StepUpAuthorizationErrorHandler();

    Boolean handle(HttpResponse.ResponseInfo info) {
        return Mono.from(this.handler.handle(null, info, McpTransportContext.EMPTY)).block();
    }

    @Test
    void insufficient_scope_403은_step_up_예외로_올린다() {
        assertThatThrownBy(() -> handle(info(403, "Bearer error=\"insufficient_scope\", scope=\"products:write\"")))
                .isInstanceOf(StepUpRequiredException.class)
                .satisfies(error -> assertThat(((StepUpRequiredException) error).scopes()).containsExactly("products:write"));
    }

    @Test
    void insufficient_scope가_아닌_403은_step_up을_시작하지_않는다() {
        assertThat(handle(info(403, null))).isFalse();
        assertThat(handle(info(403, "Bearer error=\"invalid_token\""))).isFalse();
    }

    @Test
    void _401은_다시_보내지_않는다() {
        assertThat(handle(info(401, "Bearer resource_metadata=\"x\""))).isFalse();
    }
}
