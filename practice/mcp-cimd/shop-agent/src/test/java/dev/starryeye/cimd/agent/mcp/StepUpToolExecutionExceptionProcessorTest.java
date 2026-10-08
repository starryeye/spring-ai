package dev.starryeye.cimd.agent.mcp;

import dev.starryeye.cimd.agent.security.StepUpRequiredException;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepUpToolExecutionExceptionProcessorTest {

    StepUpToolExecutionExceptionProcessor processor = new StepUpToolExecutionExceptionProcessor(
            DefaultToolExecutionExceptionProcessor.builder().build());

    static ToolExecutionException failed(Throwable cause) {
        return new ToolExecutionException(DefaultToolDefinition.builder().name("updateStock").description("d")
                .inputSchema("{}").build(), cause);
    }

    @Test
    void 감싸인_step_up_예외를_tool_이름과_함께_다시_던진다() {
        RuntimeException wrapped = new RuntimeException("SDK가 감쌈",
                new StepUpRequiredException(List.of("products:write"), null));

        assertThatThrownBy(() -> this.processor.process(failed(wrapped)))
                .isInstanceOf(StepUpRequiredException.class)
                .satisfies(error -> {
                    StepUpRequiredException stepUp = (StepUpRequiredException) error;
                    assertThat(stepUp.scopes()).containsExactly("products:write");
                    assertThat(stepUp.tool()).isEqualTo("updateStock");
                });
    }

    @Test
    void 다른_예외는_기본_처리대로_LLM에게_줄_문장이_된다() {
        assertThat(this.processor.process(failed(new IllegalStateException("재고 서버 오류")))).contains("재고 서버 오류");
    }
}
