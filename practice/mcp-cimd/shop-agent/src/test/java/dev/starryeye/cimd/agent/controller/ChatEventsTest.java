package dev.starryeye.cimd.agent.controller;

import dev.starryeye.cimd.agent.security.StepUpRequiredException;
import dev.starryeye.cimd.agent.security.StepUpState;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
    void step_up_event를_보내기_전에_onInterrupted를_부른다() {
        // 호출 순서를 직접 보려고, callback과 event 둘 다 같은 list에 기록한다.
        List<String> order = new CopyOnWriteArrayList<>();
        Flux<String> content = Flux.error(new StepUpRequiredException(List.of("orders:write"), "checkout"));

        List<ServerSentEvent<String>> events = ChatEvents.of(content, new StepUpState(), () -> order.add("onInterrupted"))
                .doOnNext(event -> order.add(event.event()))
                .collectList().block();

        assertThat(order).containsExactly("onInterrupted", "step-up");
        assertThat(events).extracting(ServerSentEvent::event).containsExactly("step-up");
    }

    @Test
    void step_up도_tool_unavailable도_아닌_예외는_onInterrupted를_부르지_않는다() {
        AtomicBoolean called = new AtomicBoolean();

        assertThatThrownBy(() -> ChatEvents.of(Flux.error(new IllegalStateException("모델 오류")), new StepUpState(),
                () -> called.set(true)).collectList().block())
                .isInstanceOf(IllegalStateException.class);

        assertThat(called).isFalse();
    }

    @Test
    void 목록에_없는_tool을_부르면_되돌리고_tool_unavailable_event를_보낸다() {
        AtomicBoolean interrupted = new AtomicBoolean();
        Flux<String> content = Flux.concat(Flux.just("잠시만요"),
                Flux.error(new IllegalStateException("No ToolCallback found for tool name: updateStock")));

        List<ServerSentEvent<String>> events = ChatEvents.of(content, new StepUpState(), () -> interrupted.set(true))
                .collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("message", "tool-unavailable");
        assertThat(events.get(1).data()).isEqualTo("{\"tool\":\"updateStock\"}");
        assertThat(interrupted).isTrue();
    }

    @Test
    void 다른_IllegalStateException은_그대로_오류다() {
        AtomicBoolean interrupted = new AtomicBoolean();
        Flux<String> content = Flux.error(new IllegalStateException("다른 문제"));

        assertThatThrownBy(() -> ChatEvents.of(content, new StepUpState(), () -> interrupted.set(true))
                .collectList().block()).hasMessageContaining("다른 문제");
        assertThat(interrupted).isFalse();
    }

    @Test
    void 다른_예외를_감싸고_있어도_안쪽의_목록에_없는_tool_오류를_찾는다() {
        // Spring AI와 reactor가 예외를 감쌀 수 있으므로, 원인 사슬 안쪽의 오류도 알아봐야 한다.
        Flux<String> content = Flux.error(new RuntimeException("감쌈",
                new IllegalStateException("No ToolCallback found for tool name: updateStock")));

        List<ServerSentEvent<String>> events = events(content);

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("tool-unavailable");
        assertThat(events.get(0).data()).isEqualTo("{\"tool\":\"updateStock\"}");
    }

    @Test
    void tool_unavailable_event를_보내기_전에_onInterrupted를_한_번만_부른다() {
        // 호출 순서를 직접 보려고, callback과 event 둘 다 같은 list에 기록한다.
        List<String> order = new CopyOnWriteArrayList<>();
        Flux<String> content = Flux.error(new IllegalStateException("No ToolCallback found for tool name: updateStock"));

        ChatEvents.of(content, new StepUpState(), () -> order.add("onInterrupted"))
                .doOnNext(event -> order.add(event.event()))
                .collectList().block();

        assertThat(order).containsExactly("onInterrupted", "tool-unavailable");
    }

    @Test
    void 원인_사슬이_A_B_A로_돌아도_다른_오류는_그대로_흘려보낸다() {
        RuntimeException a = new RuntimeException("A");
        RuntimeException b = new RuntimeException("B", a);
        // initCause는 자기 자신만 막는다. A → B → A 같은 고리는 만들 수 있다.
        a.initCause(b);
        AtomicBoolean interrupted = new AtomicBoolean();

        Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> catchThrowable(
                () -> ChatEvents.of(Flux.error(a), new StepUpState(), () -> interrupted.set(true))
                        .collectList().block()));

        assertThat(thrown).isSameAs(a);
        assertThat(interrupted).isFalse();
    }
}
