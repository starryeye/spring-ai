package dev.starryeye.authz.agent.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class StepUpRequiredExceptionTest {

    @Test
    void 원인_사슬이_A_B_A로_돌아도_찾기가_끝난다() {
        RuntimeException a = new RuntimeException("A");
        RuntimeException b = new RuntimeException("B", a);
        // initCause는 자기 자신만 막는다. A → B → A 같은 고리는 만들 수 있다.
        a.initCause(b);

        Optional<StepUpRequiredException> found = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> StepUpRequiredException.find(a));

        assertThat(found).isEmpty();
    }

    @Test
    void withTool은_원래_예외를_cause로_남긴다() {
        StepUpRequiredException original = new StepUpRequiredException(List.of("products:write"), null);

        StepUpRequiredException named = original.withTool("updateStock");

        assertThat(named.getCause()).isSameAs(original);
        assertThat(named.tool()).isEqualTo("updateStock");
        assertThat(named.scopes()).containsExactly("products:write");
        // 사슬 맨 앞의, tool 이름이 있는 쪽을 찾는다.
        assertThat(StepUpRequiredException.find(named)).containsSame(named);
    }
}
