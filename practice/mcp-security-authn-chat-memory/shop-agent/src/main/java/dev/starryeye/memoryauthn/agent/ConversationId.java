package dev.starryeye.memoryauthn.agent;

import org.springframework.security.core.Authentication;

/**
 * {@code conversationId} 를 <b>서버가</b> 만든다. 클라이언트는 label 만 고른다.
 *
 * <p>부모 practice {@code chat-memory} 는 {@code conversationId} 를 통째로
 * 클라이언트에게 받았다. 사용자가 한 명이라 성립했던 것이고, 사용자가 둘이 되는 순간
 * 남의 대화 ID 를 넣으면 그대로 읽히는 구멍이 된다.
 *
 * <p>여기서는 접두사({@code <사용자>:})를 서버가 강제하므로 label 에 무엇을 넣어도
 * 자기 네임스페이스를 벗어나지 못한다. {@link #sanitize} 한 줄이 그 전부다 —
 * 구분자를 지우지 않으면 label 에 {@code :} 를 넣어 접두사를 위조할 수 있다.
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
        return authentication.getName() + SEPARATOR;
    }

    /**
     * 구분자와 경로 문자를 제거한다. 이것이 격리의 전부이므로
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
