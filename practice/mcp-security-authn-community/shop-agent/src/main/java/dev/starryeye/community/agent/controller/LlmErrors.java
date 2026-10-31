package dev.starryeye.community.agent.controller;

import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.ErrorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * Claude API 오류를 사용자가 읽을 안내 문장으로 바꾼다.
 *
 * <p>그대로 두면 답을 보내기 전의 오류는 화면에 {@code 오류: HTTP 500}으로만 남는다.
 * 그러면 key 오류인지, 잔액 부족인지, 요청 한도인지 구분할 수 없다.
 * 원인은 로그에 남기고, 화면에는 사용자가 할 일을 보여 준다.
 */
final class LlmErrors {

    private static final Logger log = LoggerFactory.getLogger(LlmErrors.class);

    private LlmErrors() {
    }

    /**
     * 원인 사슬에서 Claude API 오류를 찾는다.
     * {@code initCause}는 자기 자신만 막으므로 A → B → A 같은 고리가 생길 수 있다.
     * 한 번 본 예외를 다시 만나면 멈춘다.
     */
    static Optional<AnthropicException> find(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof AnthropicException anthropic) {
                return Optional.of(anthropic);
            }
        }
        return Optional.empty();
    }

    /** {@link #find}가 찾은 오류의 안내 문장을 돌려주고, 오류는 로그에 남긴다. */
    static String message(Throwable error) {
        AnthropicException anthropic = find(error).orElseThrow();
        log.warn("Claude API 호출 실패: {}", anthropic.getMessage());
        if (anthropic instanceof AnthropicServiceException service) {
            return message(service);
        }
        if (anthropic instanceof AnthropicIoException || anthropic instanceof AnthropicRetryableException) {
            return "Claude API에 연결하지 못했습니다. 네트워크를 확인해 주세요.";
        }
        return "Claude API 요청이 실패했습니다.";
    }

    private static String message(AnthropicServiceException error) {
        String type = error.errorType().map(ErrorType::toString).orElse("");
        // 잔액 부족은 type이 billing_error이거나, invalid_request_error의 message로 온다.
        if (type.equals("billing_error") || String.valueOf(error.body()).contains("credit balance")) {
            return "Claude API 잔액이 부족합니다. Claude Console에서 잔액을 확인해 주세요.";
        }
        return switch (type) {
            case "authentication_error" -> "Claude API key가 올바르지 않습니다. practice/secrets.yml의 key를 확인해 주세요.";
            case "permission_error" -> "이 API key로는 이 기능을 쓸 수 없습니다. key의 권한을 확인해 주세요.";
            case "not_found_error" -> "설정한 Claude 모델을 찾을 수 없습니다. 모델 이름을 확인해 주세요.";
            case "rate_limit_error" -> "Claude API 요청 한도를 넘었습니다. 잠시 뒤 다시 시도해 주세요.";
            case "overloaded_error", "api_error", "timeout_error" ->
                    "Claude API가 지금 응답하지 못합니다. 잠시 뒤 다시 시도해 주세요.";
            default -> "Claude API 요청이 실패했습니다(HTTP " + error.statusCode() + ").";
        };
    }
}
