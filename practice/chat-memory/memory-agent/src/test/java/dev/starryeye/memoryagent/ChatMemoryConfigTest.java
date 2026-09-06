package dev.starryeye.memoryagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatMemory 자체의 동작을 LLM 없이 검증한다.
 * 이 practice 의 주제(이어짐 / 격리 / 창 넘침)가 전부 여기서 재현된다.
 */
class ChatMemoryConfigTest {

    private ChatMemory chatMemoryWithMaxMessages(int maxMessages) {
        return new ChatMemoryConfig().chatMemory(new InMemoryChatMemoryRepository(), maxMessages);
    }

    @Test
    void 같은_대화_ID_로_넣으면_그대로_나온다() {
        ChatMemory chatMemory = chatMemoryWithMaxMessages(20);

        chatMemory.add("alpha", List.of(new UserMessage("내 이름은 스타리야")));
        chatMemory.add("alpha", List.of(new AssistantMessage("반가워요 스타리님")));

        List<Message> messages = chatMemory.get("alpha");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText()).isEqualTo("내 이름은 스타리야");
        assertThat(messages.get(1).getText()).isEqualTo("반가워요 스타리님");
    }

    /** 이 practice 의 핵심. 대화 ID 가 곧 격리 경계다. */
    @Test
    void 다른_대화_ID_끼리는_섞이지_않는다() {
        ChatMemory chatMemory = chatMemoryWithMaxMessages(20);

        chatMemory.add("alpha", List.of(new UserMessage("내 이름은 스타리야")));

        assertThat(chatMemory.get("beta")).isEmpty();
        assertThat(chatMemory.get("alpha")).hasSize(1);
    }

    @Test
    void 창을_넘기면_오래된_것부터_밀려난다() {
        ChatMemory chatMemory = chatMemoryWithMaxMessages(2);

        chatMemory.add("alpha", List.of(new UserMessage("첫번째")));
        chatMemory.add("alpha", List.of(new UserMessage("두번째")));
        chatMemory.add("alpha", List.of(new UserMessage("세번째")));

        List<Message> messages = chatMemory.get("alpha");

        assertThat(messages).hasSize(2);
        assertThat(messages).extracting(Message::getText)
                .containsExactly("두번째", "세번째")
                .doesNotContain("첫번째");
    }

    @Test
    void 비우면_사라진다() {
        ChatMemory chatMemory = chatMemoryWithMaxMessages(20);
        chatMemory.add("alpha", List.of(new UserMessage("내 이름은 스타리야")));

        chatMemory.clear("alpha");

        assertThat(chatMemory.get("alpha")).isEmpty();
    }
}
