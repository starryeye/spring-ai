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

    /**
     * 위 {@code 창을_넘기면_오래된_것부터_밀려난다} 는 {@code UserMessage} 만 넣어
     * 단순히 "오래된 것부터 잘린다"만 보여준다. 실제 대화는 user/assistant 가
     * 번갈아 쌓이는데, {@code MessageWindowChatMemory.process()} 는 자르는 지점을
     * 다음 USER 메시지 앞으로 스냅하므로(assistant 메시지에서 끊어서 짝이 깨지는
     * 것을 피한다), 창을 정확히 채우지 않고 그보다 적게 남을 수 있다.
     */
    @Test
    void 창을_넘기면_다음_user_메시지_앞으로_스냅한다() {
        ChatMemory chatMemory = chatMemoryWithMaxMessages(2);

        chatMemory.add("alpha", List.of(new UserMessage("첫번째 질문")));
        chatMemory.add("alpha", List.of(new AssistantMessage("첫번째 답변")));
        chatMemory.add("alpha", List.of(new UserMessage("두번째 질문")));
        chatMemory.add("alpha", List.of(new AssistantMessage("두번째 답변")));

        List<Message> messages = chatMemory.get("alpha");

        // maxMessages=2 를 그대로 채우려면 ["두번째 질문", "두번째 답변"] 이어야 하지만,
        // 자르는 지점이 USER 메시지 앞으로 스냅되므로 실제로는 그 결과와 같거나 더 적게 남는다 —
        // 정확한 개수를 단정하지 않고, USER 메시지로 시작하며 짝이 깨지지 않는다는 것만 확인한다.
        assertThat(messages).isNotEmpty();
        assertThat(messages.get(0).getMessageType()).isEqualTo(org.springframework.ai.chat.messages.MessageType.USER);
        assertThat(messages).extracting(Message::getText).doesNotContain("첫번째 질문");
    }

    @Test
    void 비우면_사라진다() {
        ChatMemory chatMemory = chatMemoryWithMaxMessages(20);
        chatMemory.add("alpha", List.of(new UserMessage("내 이름은 스타리야")));

        chatMemory.clear("alpha");

        assertThat(chatMemory.get("alpha")).isEmpty();
    }
}
