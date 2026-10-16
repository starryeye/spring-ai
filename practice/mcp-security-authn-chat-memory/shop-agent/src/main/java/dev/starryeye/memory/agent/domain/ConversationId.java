package dev.starryeye.memory.agent.domain;

import org.springframework.security.core.Authentication;

/**
 * {@code conversationId}를 <b>서버가</b> 만든다. client는 label만 고른다.
 *
 * <p>{@code chat-memory} practice(MCP가 없는 메모리 practice)는 {@code conversationId}를 통째로
 * client에게서 받는다. 사용자가 한 명이라 그래도 되는 것이다.
 * 사용자가 둘이 되면, 남의 대화 ID를 넣어 그 대화를 그대로 읽을 수 있는 구멍이 된다.
 *
 * <p>여기서는 서버가 접두사({@code <사용자>:})를 붙이므로, label에 무엇을 넣어도
 * 자기 namespace를 벗어나지 못한다. label 쪽 격리는 {@link #sanitize} 한 줄이 전부다.
 * 구분자를 지우지 않으면 label에 {@code :}를 넣어 접두사를 위조할 수 있다.
 *
 * <p>사용자 이름(token의 {@code sub})에도 구분자가 들어갈 수 있다.
 * 외부 IdP의 {@code sub}는 문자 집합을 보장하지 않기 때문이다.
 * 그대로 쓰면 {@code alice:x}의 대화 ID({@code alice:x:default})가 alice의 접두사 {@code alice:}로 시작해
 * alice의 목록에 섞인다.
 * 그래서 {@link #escape}로 {@code %}와 {@code :}를 percent-encoding해, 접두사 안에 구분자가 남지 않게 한다.
 * {@code alice}처럼 두 글자가 없는 이름은 그대로다.
 */
public final class ConversationId {

    private static final String SEPARATOR = ":";

    private static final String DEFAULT_LABEL = "default";

    private ConversationId() {
    }

    /** 현재 사용자의 대화 ID다. label이 비어 있으면 {@code default}를 쓴다. */
    public static String of(Authentication authentication, String label) {
        return prefixOf(authentication) + sanitize(label);
    }

    /** 현재 사용자의 namespace 접두사다. 목록을 거를 때 쓴다. */
    public static String prefixOf(Authentication authentication) {
        return escape(authentication.getName()) + SEPARATOR;
    }

    /**
     * {@code %}를 먼저 {@code %25}로, {@code :}를 {@code %3A}로 바꾼다.
     * {@code %}도 바꾸므로 서로 다른 이름은 서로 다른 결과가 된다
     * ({@code alice:x} → {@code alice%3Ax}, {@code alice%3Ax} → {@code alice%253Ax}).
     * 결과에 구분자가 없으므로, 한 사용자의 대화 ID가 다른 사용자의 접두사로 시작할 수 없다.
     */
    private static String escape(String username) {
        return username.replace("%", "%25").replace(SEPARATOR, "%3A");
    }

    /**
     * 구분자와 경로 문자를 지운다. label 쪽 격리는 이것이 전부이므로
     * {@code ConversationIdTest}가 이 동작을 고정한다.
     */
    private static String sanitize(String label) {
        if (label == null || label.isBlank()) {
            return DEFAULT_LABEL;
        }
        String cleaned = label.trim().replaceAll("[^A-Za-z0-9가-힣_-]", "_");
        return cleaned.isBlank() ? DEFAULT_LABEL : cleaned;
    }
}
