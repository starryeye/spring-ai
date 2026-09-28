package dev.starryeye.authz.agent.security;

import java.util.List;
import java.util.Optional;

/**
 * MCP Server가 {@code 403 insufficient_scope}로 더 넓은 scope를 요구했다(안내서 10장).
 *
 * <p>agent는 이 예외를 LLM에게 tool 오류 문장으로 넘기지 않는다.
 * 채팅 응답까지 올려서 사용자에게 consent 카드를 보여 준다.
 */
public class StepUpRequiredException extends RuntimeException {

    private final List<String> scopes;

    private final String tool;

    public StepUpRequiredException(List<String> scopes, String tool) {
        super("추가 권한이 필요하다: " + String.join(" ", scopes) + ((tool == null) ? "" : " (tool=" + tool + ")"));
        this.scopes = List.copyOf(scopes);
        this.tool = tool;
    }

    public List<String> scopes() {
        return this.scopes;
    }

    /** 권한이 모자랐던 tool 이름이다. transport 단계에서는 알 수 없어 {@code null}일 수 있다. */
    public String tool() {
        return this.tool;
    }

    public StepUpRequiredException withTool(String tool) {
        return new StepUpRequiredException(this.scopes, tool);
    }

    /** 원인 사슬에서 이 예외를 찾는다. MCP SDK와 Spring AI가 예외를 감쌀 수 있어서다. */
    public static Optional<StepUpRequiredException> find(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof StepUpRequiredException stepUp) {
                return Optional.of(stepUp);
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return Optional.empty();
    }
}
