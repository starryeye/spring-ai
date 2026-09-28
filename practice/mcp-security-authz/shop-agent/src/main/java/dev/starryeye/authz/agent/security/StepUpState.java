package dev.starryeye.authz.agent.security;

import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 한 사용자의 step-up 진행 상태다. HTTP session에 둔다.
 *
 * <p>{@code pending}은 consent 화면에 가 있는 동안 기다리는 scope다. login이 끝나거나 실패하면 비운다.
 * {@code attempted}는 이 session에서 한 번 step-up을 거친 scope다.
 * 그 scope로 다시 {@code 403}이 오면 사용자가 허락하지 않은 것이므로, consent 카드를 다시 띄우지 않는다.
 * MCP Security Best Practices의 client 지침("거절된 scope로 권한 상승을 되풀이하지 않는다")을 따른다.
 */
public final class StepUpState implements Serializable {

    static final String ATTRIBUTE = StepUpState.class.getName();

    private final Set<String> attempted = ConcurrentHashMap.newKeySet();

    private volatile List<String> pending = List.of();

    public static StepUpState of(HttpSession session) {
        synchronized (session) {
            StepUpState existing = existing(session);
            if (existing != null) {
                return existing;
            }
            StepUpState created = new StepUpState();
            session.setAttribute(ATTRIBUTE, created);
            return created;
        }
    }

    public static StepUpState existing(HttpSession session) {
        return (session != null && session.getAttribute(ATTRIBUTE) instanceof StepUpState state) ? state : null;
    }

    public void start(Collection<String> scopes) {
        this.pending = List.copyOf(scopes);
        this.attempted.addAll(scopes);
    }

    /** 기다리던 scope를 돌려주고 비운다. */
    public List<String> finish() {
        List<String> finished = this.pending;
        this.pending = List.of();
        return finished;
    }

    public boolean isPending() {
        return !this.pending.isEmpty();
    }

    public boolean attempted(Collection<String> scopes) {
        return scopes.stream().anyMatch(this.attempted::contains);
    }

    /** 사용자가 명시적으로 다시 요청할 때 시도 기록을 지운다. */
    public void retry(Collection<String> scopes) {
        this.attempted.removeAll(scopes);
    }
}
