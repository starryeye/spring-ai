package dev.starryeye.memoryauthn.agent;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이 practice 의 격리는 전부 이 클래스 한 곳에 있다.
 * label 에 무엇을 넣어도 접두사를 벗어나지 못해야 한다.
 */
class ConversationIdTest {

    private Authentication user(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", List.of());
    }

    @Test
    void 사용자_이름이_접두사가_된다() {
        assertThat(ConversationId.of(user("alice"), "default")).isEqualTo("alice:default");
    }

    @Test
    void label_이_없으면_default_다() {
        assertThat(ConversationId.of(user("alice"), null)).isEqualTo("alice:default");
        assertThat(ConversationId.of(user("alice"), "")).isEqualTo("alice:default");
        assertThat(ConversationId.of(user("alice"), "   ")).isEqualTo("alice:default");
    }

    /** 핵심. bob 이 alice 의 네임스페이스를 노려도 자기 것으로 강제된다. */
    @Test
    void label_에_구분자를_넣어도_남의_네임스페이스로_못_간다() {
        String id = ConversationId.of(user("bob"), "alice:default");

        assertThat(id).startsWith("bob:");
        assertThat(id).doesNotContain("alice:");
    }

    @Test
    void 경로_문자를_넣어도_벗어나지_못한다() {
        String id = ConversationId.of(user("bob"), "../alice/default");

        assertThat(id).startsWith("bob:");
        assertThat(id).doesNotContain("..");
        assertThat(id).doesNotContain("/");
    }

    @Test
    void 접두사만_따로_얻을_수_있다() {
        assertThat(ConversationId.prefixOf(user("alice"))).isEqualTo("alice:");
    }

    @Test
    void 서로_다른_사용자는_서로_다른_ID_를_얻는다() {
        assertThat(ConversationId.of(user("alice"), "default"))
                .isNotEqualTo(ConversationId.of(user("bob"), "default"));
    }
}
