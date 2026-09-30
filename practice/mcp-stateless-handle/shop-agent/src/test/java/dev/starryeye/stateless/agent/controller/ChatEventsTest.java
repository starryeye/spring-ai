package dev.starryeye.stateless.agent.controller;

import dev.starryeye.stateless.agent.security.StepUpRequiredException;
import dev.starryeye.stateless.agent.security.StepUpState;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatEventsTest {

    StepUpState state = new StepUpState();

    List<ServerSentEvent<String>> events(Flux<String> content) {
        return ChatEvents.of(content, this.state).collectList().block();
    }

    @Test
    void 답변_토막은_앞의_공백까지_JSON_문자열로_감싼_message_event다() {
        List<ServerSentEvent<String>> events = events(Flux.just(" 재고", "는 7개"));

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("message", "message");
        assertThat(events).extracting(ServerSentEvent::data).containsExactly("\" 재고\"", "\"는 7개\"");
    }

    @Test
    void step_up_예외는_consent_카드_event가_된다() {
        List<ServerSentEvent<String>> events = events(Flux.concat(Flux.just("확인해 볼게요"),
                Flux.error(new RuntimeException("감쌈",
                        new StepUpRequiredException(List.of("products:write"), "updateStock")))));

        assertThat(events.get(1).event()).isEqualTo("step-up");
        assertThat(events.get(1).data()).isEqualTo("{\"scope\":\"products:write\",\"tool\":\"updateStock\","
                + "\"url\":\"/oauth2/authorization/authserver?step_up=products:write\"}");
        // 카드의 url로 가면 resolver는 challenge된 scope만 받아 준다.
        assertThat(this.state.challenged("products:write")).isTrue();
    }

    @Test
    void 이미_시도한_scope면_거절_안내_event가_된다() {
        this.state.start(List.of("products:write"));
        this.state.finish();

        List<ServerSentEvent<String>> events = events(
                Flux.error(new StepUpRequiredException(List.of("products:write"), "updateStock")));

        assertThat(events.get(0).event()).isEqualTo("step-up-declined");
        assertThat(events.get(0).data()).contains("\"url\":\"/step-up/retry?scope=products:write\"");
        // 거절 안내의 "다시 요청"도 결국 step_up으로 가므로 여기서도 challenge를 남긴다.
        assertThat(this.state.challenged("products:write")).isTrue();
    }

    @Test
    void 다른_예외는_그대로_흘려보낸다() {
        assertThatThrownBy(() -> events(Flux.error(new IllegalStateException("모델 오류"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void step_up_event를_보내기_전에_onStepUp을_부른다() {
        AtomicBoolean called = new AtomicBoolean();
        Flux<String> content = Flux.error(new StepUpRequiredException(List.of("orders:write"), "checkout"));

        List<ServerSentEvent<String>> events = ChatEvents.of(content, new StepUpState(), () -> called.set(true))
                .collectList().block();

        assertThat(called).isTrue();
        assertThat(events).extracting(ServerSentEvent::event).containsExactly("step-up");
    }
}
