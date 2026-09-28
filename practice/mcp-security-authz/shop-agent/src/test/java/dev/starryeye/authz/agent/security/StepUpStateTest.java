package dev.starryeye.authz.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepUpStateTest {

    @Test
    void session마다_하나를_만들어_다시_쓴다() {
        MockHttpSession session = new MockHttpSession();

        assertThat(StepUpState.existing(session)).isNull();
        assertThat(StepUpState.of(session)).isSameAs(StepUpState.of(session));
    }

    @Test
    void 시작하면_기다리는_중이고_시도한_scope로_남는다() {
        StepUpState state = new StepUpState();

        state.start(List.of("products:write"));

        assertThat(state.isPending()).isTrue();
        assertThat(state.attempted(List.of("products:write"))).isTrue();
        assertThat(state.finish()).containsExactly("products:write");
        assertThat(state.isPending()).isFalse();
        // login이 끝나도 시도 기록은 남는다. 같은 scope로 step-up을 되풀이하지 않기 위해서다.
        assertThat(state.attempted(List.of("products:write"))).isTrue();
    }

    @Test
    void 다시_요청하면_시도_기록을_지운다() {
        StepUpState state = new StepUpState();
        state.start(List.of("products:write"));
        state.finish();

        state.retry(List.of("products:write"));

        assertThat(state.attempted(List.of("products:write"))).isFalse();
    }

    @Test
    void MCP_Server가_요구한_scope만_challenged다() {
        StepUpState state = new StepUpState();

        assertThat(state.challenged("products:write")).isFalse();

        state.challenge(List.of("products:write"));

        assertThat(state.challenged("products:write")).isTrue();
        assertThat(state.challenged("products:delete")).isFalse();
    }
}
