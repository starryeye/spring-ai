package dev.starryeye.visibility.agent.controller;

import dev.starryeye.visibility.agent.config.McpSecurityConfig;
import dev.starryeye.visibility.agent.security.StepUpAuthorizationRequestResolver;
import dev.starryeye.visibility.agent.security.StepUpRequiredException;
import dev.starryeye.visibility.agent.security.StepUpState;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 채팅 답을 SSE event로 바꾼다(안내서 10장).
 *
 * <p>답변 토막은 {@code message} event다.
 * tool 호출이 step-up을 요구하면 답을 멈추고 {@code step-up} event(consent 카드)를 보낸다.
 * 이미 step-up을 거친 scope면 {@code step-up-declined} event(거절 안내)를 보낸다.
 * 사용자가 거절한 권한을 카드로 되풀이해 묻지 않기 위해서다.
 * 모델이 이 사용자의 목록에 없는 tool을 부르면 Spring AI가 오류로 stream을 끝낸다.
 * 그대로 두면 화면에는 오류만 남고, 결과 없는 tool 호출이 대화 기억에 남는다.
 * 그래서 이 경우는 {@code tool-unavailable} event로 끝낸다.
 */
public final class ChatEvents {

    static final String MESSAGE = "message";

    static final String STEP_UP = "step-up";

    static final String STEP_UP_DECLINED = "step-up-declined";

    static final String TOOL_UNAVAILABLE = "tool-unavailable";

    /**
     * Spring AI 2.0.1에는 이 오류를 위한 전용 예외 type이 없어서 message 글자로 알아본다.
     * 목록에 없는 tool을 모델이 부를 때 던지는 message의 앞부분이다.
     * Spring AI를 올리면 다시 확인한다.
     */
    private static final String NO_TOOL_CALLBACK = "No ToolCallback found for tool name: ";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ChatEvents() {
    }

    public static Flux<ServerSentEvent<String>> of(Flux<String> content, StepUpState state) {
        return of(content, state, () -> {
        });
    }

    /**
     * {@code onInterrupted}는 tool 결과 없이 끊긴 turn을 끝내기 직전에 부른다.
     * step-up이 필요할 때와, 모델이 이 사용자의 목록에 없는 tool을 불렀을 때다.
     * agent는 여기서 끊긴 turn을 대화 기억에서 되돌린다.
     */
    public static Flux<ServerSentEvent<String>> of(Flux<String> content, StepUpState state, Runnable onInterrupted) {
        return content.map(text -> event(MESSAGE, JSON.writeValueAsString(text)))
                .onErrorResume(error -> StepUpRequiredException.find(error).isPresent(), error -> {
                    onInterrupted.run();
                    return Flux.just(stepUp(StepUpRequiredException.find(error).orElseThrow(), state));
                })
                .onErrorResume(error -> unavailableTool(error).isPresent(), error -> {
                    onInterrupted.run();
                    return Flux.just(event(TOOL_UNAVAILABLE,
                            JSON.writeValueAsString(Map.of("tool", unavailableTool(error).orElseThrow()))));
                });
    }

    /**
     * 원인 사슬에서 "목록에 없는 tool" 오류를 찾아 그 tool 이름을 돌려준다.
     * {@code initCause}는 자기 자신만 막으므로 A → B → A 같은 고리가 생길 수 있다.
     * 한 번 본 예외를 다시 만나면 멈춘다. {@link StepUpRequiredException#find}와 같다.
     */
    static Optional<String> unavailableTool(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof IllegalStateException && cause.getMessage() != null
                    && cause.getMessage().startsWith(NO_TOOL_CALLBACK)) {
                return Optional.of(cause.getMessage().substring(NO_TOOL_CALLBACK.length()).trim());
            }
        }
        return Optional.empty();
    }

    /**
     * 카드와 거절 안내 모두 결국 {@code step_up} parameter로 authorization request를 보낸다.
     * resolver는 challenge된 scope만 받으므로, 두 경우 모두 event를 만들기 전에 기록한다.
     */
    private static ServerSentEvent<String> stepUp(StepUpRequiredException required, StepUpState state) {
        state.challenge(required.scopes());
        String scope = String.join(" ", required.scopes());
        boolean declined = state.attempted(required.scopes());
        String url = declined
                ? UriComponentsBuilder.fromPath("/step-up/retry").queryParam("scope", scope)
                        .encode().build().toUriString()
                : UriComponentsBuilder.fromPath(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
                                + "/" + McpSecurityConfig.REGISTRATION_ID)
                        .queryParam(StepUpAuthorizationRequestResolver.PARAMETER, scope)
                        .encode().build().toUriString();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("scope", scope);
        card.put("tool", required.tool());
        card.put("url", url);
        return event(declined ? STEP_UP_DECLINED : STEP_UP, JSON.writeValueAsString(card));
    }

    /**
     * data는 항상 JSON이다.
     * SSE는 {@code data:} 뒤의 공백 하나를 지우므로, 답변 토막을 그대로 보내면 토막 앞의 공백이 사라진다.
     */
    private static ServerSentEvent<String> event(String name, String json) {
        return ServerSentEvent.<String>builder(json).event(name).build();
    }
}
