package dev.starryeye.memory.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어느 LLM을 쓸지는 profile이 정한다.
 * 실제 설정 파일을 읽어, 기본은 API key 없이 뜨고 {@code anthropic} profile은 Claude를 쓰는지 본다.
 */
class LlmProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class,
                    AnthropicChatAutoConfiguration.class));

    @Test
    void 기본으로는_Claude_모델을_만들지_않아_API_key가_없어도_뜬다() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AnthropicChatModel.class);
        });
    }

    @Test
    void anthropic_profile이면_설정한_모델로_Claude를_쓴다() {
        this.runner.withPropertyValues("spring.profiles.active=anthropic", "ANTHROPIC_API_KEY=test-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(AnthropicChatModel.class);
                    assertThat(context.getBean(AnthropicChatModel.class).getOptions().getModel())
                            .isEqualTo("claude-haiku-5-5");
                });
    }
}
