package dev.starryeye.stateless.agent.mcp;

import dev.starryeye.stateless.agent.security.StepUpRequiredException;

import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

import java.util.Optional;

/**
 * tool 호출이 step-up을 요구하면 LLM에게 오류 문장으로 넘기지 않고 다시 던진다.
 *
 * <p>Spring AI는 tool 예외를 문장으로 바꿔 LLM에게 돌려준다.
 * 그러면 LLM은 "권한이 없습니다" 같은 답을 지어낼 뿐 사용자는 권한을 줄 기회를 얻지 못한다.
 * 그래서 이 예외만 채팅 응답까지 올린다. 나머지는 {@code delegate}가 처리한다.
 */
public class StepUpToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

    private final ToolExecutionExceptionProcessor delegate;

    public StepUpToolExecutionExceptionProcessor(ToolExecutionExceptionProcessor delegate) {
        this.delegate = delegate;
    }

    @Override
    public String process(ToolExecutionException exception) {
        Optional<StepUpRequiredException> stepUp = StepUpRequiredException.find(exception);
        if (stepUp.isPresent()) {
            throw stepUp.get().withTool(exception.getToolDefinition().name());
        }
        return this.delegate.process(exception);
    }
}
