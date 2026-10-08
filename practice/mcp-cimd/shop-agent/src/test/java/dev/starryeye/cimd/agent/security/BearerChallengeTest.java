package dev.starryeye.cimd.agent.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BearerChallengeTest {

    @Test
    void insufficient_scope와_scope를_읽는다() {
        BearerChallenge challenge = BearerChallenge.parse("Bearer error=\"insufficient_scope\", "
                + "scope=\"products:write\", resource_metadata=\"http://localhost:8171/.well-known/x\"").orElseThrow();

        assertThat(challenge.error()).isEqualTo("insufficient_scope");
        assertThat(challenge.scopes()).containsExactly("products:write");
        assertThat(challenge.insufficientScope()).isTrue();
    }

    @Test
    void 여러_scope는_공백으로_나눈다() {
        assertThat(BearerChallenge.parse("Bearer error=\"insufficient_scope\", scope=\"a b\"").orElseThrow().scopes())
                .containsExactly("a", "b");
    }

    @Test
    void Bearer가_아니면_읽지_않는다() {
        assertThat(BearerChallenge.parse("Basic realm=\"x\"")).isEmpty();
        assertThat(BearerChallenge.parse(null)).isEmpty();
    }

    @Test
    void scope가_없는_insufficient_scope는_step_up_대상이_아니다() {
        assertThat(BearerChallenge.parse("Bearer error=\"insufficient_scope\"").orElseThrow().insufficientScope())
                .isFalse();
    }
}
