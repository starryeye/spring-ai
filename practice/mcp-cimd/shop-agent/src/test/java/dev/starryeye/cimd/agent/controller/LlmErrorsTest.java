package dev.starryeye.cimd.agent.controller;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmErrorsTest {

    private static final Headers NO_HEADERS = Headers.builder().build();

    private static JsonValue body(String type, String message) {
        return JsonValue.from(Map.of("type", "error", "error", Map.of("type", type, "message", message)));
    }

    @Test
    void 잘못된_key는_key를_확인하라고_안내한다() {
        Throwable error = UnauthorizedException.builder().headers(NO_HEADERS)
                .body(body("authentication_error", "invalid x-api-key")).build();

        assertThat(LlmErrors.message(error)).contains("key가 올바르지 않습니다");
    }

    @Test
    void 권한이_없으면_key의_권한을_확인하라고_안내한다() {
        Throwable error = PermissionDeniedException.builder().headers(NO_HEADERS)
                .body(body("permission_error", "not allowed")).build();

        assertThat(LlmErrors.message(error)).contains("권한을 확인");
    }

    @Test
    void 없는_모델은_모델_이름을_확인하라고_안내한다() {
        Throwable error = NotFoundException.builder().headers(NO_HEADERS)
                .body(body("not_found_error", "model: claude-x")).build();

        assertThat(LlmErrors.message(error)).contains("모델 이름을 확인");
    }

    @Test
    void 잔액_부족은_invalid_request_error의_message로도_알아본다() {
        Throwable error = BadRequestException.builder().headers(NO_HEADERS)
                .body(body("invalid_request_error", "Your credit balance is too low")).build();

        assertThat(LlmErrors.message(error)).contains("잔액이 부족합니다");
    }

    @Test
    void 요청_한도를_넘으면_잠시_뒤_다시_시도하라고_안내한다() {
        Throwable error = RateLimitException.builder().headers(NO_HEADERS)
                .body(body("rate_limit_error", "too many")).build();

        assertThat(LlmErrors.message(error)).contains("요청 한도");
    }

    @Test
    void 서버_과부하는_잠시_뒤_다시_시도하라고_안내한다() {
        Throwable error = InternalServerException.builder().statusCode(529).headers(NO_HEADERS)
                .body(body("overloaded_error", "Overloaded")).build();

        assertThat(LlmErrors.message(error)).contains("지금 응답하지 못합니다");
    }

    @Test
    void 연결_실패는_네트워크를_확인하라고_안내한다() {
        assertThat(LlmErrors.message(new AnthropicIoException("connection refused"))).contains("네트워크");
    }

    @Test
    void 처음_보는_오류는_HTTP_상태를_보여_준다() {
        Throwable error = BadRequestException.builder().headers(NO_HEADERS)
                .body(body("invalid_request_error", "something else")).build();

        assertThat(LlmErrors.message(error)).contains("HTTP 400");
    }

    @Test
    void 감싼_예외의_원인_사슬에서_찾는다() {
        Throwable error = new IllegalStateException("Stream processing failed", new RuntimeException(
                UnauthorizedException.builder().headers(NO_HEADERS)
                        .body(body("authentication_error", "invalid x-api-key")).build()));

        assertThat(LlmErrors.find(error)).isPresent();
    }

    @Test
    void Claude_API_오류가_아니면_찾지_않는다() {
        assertThat(LlmErrors.find(new IllegalStateException("모델 오류"))).isEmpty();
    }

    @Test
    void 원인_고리가_있어도_끝난다() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(LlmErrors.find(a)).isEmpty();
    }
}
