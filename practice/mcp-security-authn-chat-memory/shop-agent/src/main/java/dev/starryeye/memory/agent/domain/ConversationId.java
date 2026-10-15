package dev.starryeye.memory.agent.domain;

import org.springframework.security.core.Authentication;

/**
 * {@code conversationId} 를 <b>서버가</b> 만든다. 클라이언트는 label 만 고른다.
 *
 * <p>{@code chat-memory} practice(MCP 없는 메모리 practice)는 {@code conversationId} 를 통째로
 * 클라이언트에게 받는다. 사용자가 한 명이라 성립하는 것이고, 사용자가 둘이 되는 순간
 * 남의 대화 ID 를 넣으면 그대로 읽히는 구멍이 된다.
 *
 * <p>여기서는 접두사({@code <사용자>:})를 서버가 강제하므로 label 에 무엇을 넣어도
 * 자기 네임스페이스를 벗어나지 못한다. label 쪽은 {@link #sanitize} 한 줄이 그 전부다 —
 * 구분자를 지우지 않으면 label 에 {@code :} 를 넣어 접두사를 위조할 수 있다.
 *
 * <p>사용자 이름(token 의 {@code sub})도 구분자를 품을 수 있다. 외부 IdP 의 {@code sub} 는 문자 집합을
 * 보장하지 않으므로, 그대로 쓰면 {@code alice:x} 의 대화 ID({@code alice:x:default})가 alice 의 접두사
 * {@code alice:} 에 걸려 alice 의 목록에 섞인다. 그래서 {@link #escape} 로 {@code %} 와 {@code :} 를
 * percent-encoding 해 접두사 안에 구분자가 남지 않게 한다. {@code alice} 처럼 두 글자가 없는 이름은 그대로다.
 */
public final class ConversationId {

    private static final String SEPARATOR = ":";

    private static final String DEFAULT_LABEL = "default";

    private ConversationId() {
    }

    /** 현재 사용자의 대화 ID. label 이 비어 있으면 {@code default} 를 쓴다. */
    public static String of(Authentication authentication, String label) {
        return prefixOf(authentication) + sanitize(label);
    }

    /** 현재 사용자의 네임스페이스 접두사. 목록을 거를 때 쓴다. */
    public static String prefixOf(Authentication authentication) {
        return escape(authentication.getName()) + SEPARATOR;
    }

    /**
     * {@code %} 를 먼저 {@code %25} 로, {@code :} 를 {@code %3A} 로 바꾼다. {@code %} 도 바꾸므로 서로 다른
     * 이름은 서로 다른 결과가 되고({@code alice:x} → {@code alice%3Ax}, {@code alice%3Ax} → {@code alice%253Ax}),
     * 결과에 구분자가 없으므로 한 사용자의 대화 ID 가 다른 사용자의 접두사로 시작할 수 없다.
     */
    private static String escape(String username) {
        return username.replace("%", "%25").replace(SEPARATOR, "%3A");
    }

    /**
     * 구분자와 경로 문자를 제거한다. label 쪽 격리는 이것이 전부이므로
     * {@code ConversationIdTest} 가 이 동작을 고정한다.
     */
    private static String sanitize(String label) {
        if (label == null || label.isBlank()) {
            return DEFAULT_LABEL;
        }
        String cleaned = label.trim().replaceAll("[^A-Za-z0-9가-힣_-]", "_");
        return cleaned.isBlank() ? DEFAULT_LABEL : cleaned;
    }
}
