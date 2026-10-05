package dev.starryeye.visibility.agent.security;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.util.WebUtils;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 한 사용자의 step-up 진행 상태다. HTTP session에 둔다.
 *
 * <p>{@code pending}은 consent 화면에 가 있는 동안 기다리는 scope다. login이 끝나거나 실패하면 비운다.
 * 정확히는 다음 login이 끝나거나 실패할 때까지 남는다 — 그 사이에 다른 요청이 끼어들어도 상관없다.
 * {@code attempted}는 이 session에서 한 번 step-up을 거친 scope다.
 * 그 scope로 다시 {@code 403}이 오면 사용자가 허락하지 않은 것이므로, consent 카드를 다시 띄우지 않는다.
 * MCP Security Best Practices의 client 지침("거절된 scope로 권한 상승을 되풀이하지 않는다")을 따른다.
 * {@code challenged}는 MCP Server가 {@code 403 insufficient_scope}로 실제로 요구한 scope다.
 * {@code ChatEvents}가 카드나 거절 안내를 보낼 때 {@link #challenge}를 부른다.
 * {@code step_up} parameter는 그 목록에 있는 scope만 허용한다 — 그 밖의 값은 사용자가
 * 아니라 다른 사이트가 끼워 넣었을 수 있다({@code <img>} 같은 subresource로도 이 GET을 부를 수 있다).
 *
 * <p>이 class는 {@code start}, {@code finish}, {@code retry}, {@code challenge}가 필드를 그 자리에서
 * 바꿀 뿐, {@code session.setAttribute}를 다시 부르지 않는다. 그래서 이 변경이 남으려면 session이
 * 메모리에 있고 참조로 공유돼야 한다. Spring Session처럼 attribute를 매번 외부 저장소에 직렬화해
 * 넣는 구현이라면, {@code setAttribute}를 다시 부르지 않는 이 class의 변경은 저장소에 반영되지
 * 않고 사라진다.
 */
public final class StepUpState implements Serializable {

    static final String ATTRIBUTE = StepUpState.class.getName();

    private final Set<String> attempted = ConcurrentHashMap.newKeySet();

    private final Set<String> challenged = ConcurrentHashMap.newKeySet();

    private volatile List<String> pending = List.of();

    public static StepUpState of(HttpSession session) {
        synchronized (WebUtils.getSessionMutex(session)) {
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

    /** MCP Server가 {@code 403 insufficient_scope}로 요구한 scope를 기록한다. */
    public void challenge(Collection<String> scopes) {
        this.challenged.addAll(scopes);
    }

    public boolean challenged(String scope) {
        return this.challenged.contains(scope);
    }
}
