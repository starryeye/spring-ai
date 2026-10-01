package dev.starryeye.stateless.agent.controller;

import dev.starryeye.stateless.agent.config.McpSecurityConfig;
import dev.starryeye.stateless.agent.security.StepUpAuthorizationRequestResolver;
import dev.starryeye.stateless.agent.security.StepUpRequiredException;
import dev.starryeye.stateless.agent.security.StepUpState;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 채팅 답을 SSE event로 바꾼다(안내서 10장).
 *
 * <p>답변 토막은 {@code message} event다.
 * tool 호출이 step-up을 요구하면 답을 멈추고 {@code step-up} event(consent 카드)를 보낸다.
 * 이미 step-up을 거친 scope면 {@code step-up-declined} event(거절 안내)를 보낸다.
 * 사용자가 거절한 권한을 카드로 되풀이해 묻지 않기 위해서다.
 */
public final class ChatEvents {

    static final String MESSAGE = "message";

    static final String STEP_UP = "step-up";

    static final String STEP_UP_DECLINED = "step-up-declined";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ChatEvents() {
    }

    public static Flux<ServerSentEvent<String>> of(Flux<String> content, StepUpState state) {
        return of(content, state, () -> {
        });
    }

    /**
     * {@code onStepUp}은 step-up event를 만들기 직전에 부른다.
     * agent는 여기서 끊긴 turn을 대화 기억에서 되돌린다.
     */
    public static Flux<ServerSentEvent<String>> of(Flux<String> content, StepUpState state, Runnable onStepUp) {
        return content.map(text -> event(MESSAGE, JSON.writeValueAsString(text)))
                .onErrorResume(error -> StepUpRequiredException.find(error).isPresent(), error -> {
                    onStepUp.run();
                    return Flux.just(stepUp(StepUpRequiredException.find(error).orElseThrow(), state));
                });
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
