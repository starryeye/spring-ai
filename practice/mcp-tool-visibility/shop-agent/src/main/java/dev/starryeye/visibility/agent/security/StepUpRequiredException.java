package dev.starryeye.visibility.agent.security;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        super(message(scopes, tool));
        this.scopes = List.copyOf(scopes);
        this.tool = tool;
    }

    public StepUpRequiredException(List<String> scopes, String tool, Throwable cause) {
        super(message(scopes, tool), cause);
        this.scopes = List.copyOf(scopes);
        this.tool = tool;
    }

    private static String message(List<String> scopes, String tool) {
        return "추가 권한이 필요하다: " + String.join(" ", scopes) + ((tool == null) ? "" : " (tool=" + tool + ")");
    }

    public List<String> scopes() {
        return this.scopes;
    }

    /** 권한이 모자랐던 tool 이름이다. transport 단계에서는 알 수 없어 {@code null}일 수 있다. */
    public String tool() {
        return this.tool;
    }

    /** 원래 예외를 cause로 남긴다. transport에서 던진 자리의 stack trace를 잃지 않기 위해서다. */
    public StepUpRequiredException withTool(String tool) {
        return new StepUpRequiredException(this.scopes, tool, this);
    }

    /**
     * 원인 사슬에서 이 예외를 찾는다. MCP SDK와 Spring AI가 예외를 감쌀 수 있어서다.
     * {@code initCause}는 자기 자신만 막으므로 A → B → A 같은 고리가 생길 수 있다.
     * 한 번 본 예외를 다시 만나면 멈춘다.
     */
    public static Optional<StepUpRequiredException> find(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof StepUpRequiredException stepUp) {
                return Optional.of(stepUp);
            }
        }
        return Optional.empty();
    }
}
