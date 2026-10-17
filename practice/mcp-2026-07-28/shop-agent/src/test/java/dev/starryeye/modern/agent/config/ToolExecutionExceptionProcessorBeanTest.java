package dev.starryeye.modern.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code toolExecutionExceptionProcessor} bean은 Spring AI 자동 구성의 같은 bean을 대신한다.
 * 그래서 자동 구성이 반영하던 {@code spring.ai.tools.throw-exception-on-error}도 이 bean이 직접 반영해야 한다.
 */
class ToolExecutionExceptionProcessorBeanTest {

    static ToolExecutionException failed(Throwable cause) {
        return new ToolExecutionException(DefaultToolDefinition.builder().name("getStock").description("d")
                .inputSchema("{}").build(), cause);
    }

    @Test
    void throw_exception_on_error를_켜면_tool_예외를_LLM에게_넘기지_않고_다시_던진다() {
        ToolCallingProperties properties = new ToolCallingProperties();
        properties.setThrowExceptionOnError(true);
        ToolExecutionExceptionProcessor processor = new McpSecurityConfig().toolExecutionExceptionProcessor(properties);
        ToolExecutionException failure = failed(new IllegalStateException("재고 서버 오류"));

        assertThatThrownBy(() -> processor.process(failure)).isSameAs(failure);
    }

    @Test
    void 기본값이면_tool_예외는_LLM에게_줄_문장이_된다() {
        ToolExecutionExceptionProcessor processor =
                new McpSecurityConfig().toolExecutionExceptionProcessor(new ToolCallingProperties());

        assertThat(processor.process(failed(new IllegalStateException("재고 서버 오류")))).contains("재고 서버 오류");
    }
}
