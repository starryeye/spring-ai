package dev.starryeye.modern.agent.security;

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

    private static String message(List<String> scopes, String tool) {
        return "추가 권한이 필요하다: " + String.join(" ", scopes) + ((tool == null) ? "" : " (tool=" + tool + ")");
    }

    public List<String> scopes() {
        return this.scopes;
    }

    /**
     * 권한이 모자랐던 tool 이름이다.
     * {@code tools/call}이 아닌 요청({@code tools/list}, {@code server/discover})의 {@code 403}이면 {@code null}이다.
     */
    public String tool() {
        return this.tool;
    }

    /**
     * 원인 사슬에서 이 예외를 찾는다.
     * 이 예외는 Spring AI의 tool loop와 Reactor를 지나 채팅 응답까지 올라온다.
     * 그 사이에 다른 예외에 감싸여도 찾도록 원인 사슬을 따라간다.
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
