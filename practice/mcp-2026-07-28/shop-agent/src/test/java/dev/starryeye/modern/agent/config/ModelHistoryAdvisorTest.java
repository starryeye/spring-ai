package dev.starryeye.modern.agent.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModelHistoryAdvisorTest {

    private static final ToolCall CALL = new ToolCall("t1", "function", "getStock", "{}");

    private static final ToolResponseMessage RESULT = ToolResponseMessage.builder()
            .responses(List.of(new ToolResponse("t1", "getStock", "7개")))
            .build();

    private static AssistantMessage assistant(String text, ToolCall... calls) {
        return AssistantMessage.builder().content(text).toolCalls(List.of(calls)).build();
    }

    @Test
    void metadata에_있는_thinking_block을_떼어_낸다() {
        Message answer = AssistantMessage.builder()
                .content("7개입니다.")
                .properties(Map.of("anthropicThinkingContents", List.of("block")))
                .build();

        List<Message> cleaned = ModelHistoryAdvisor.clean(List.of(new UserMessage("재고?"), answer));

        assertThat(cleaned).hasSize(2);
        assertThat(cleaned.get(1).getText()).isEqualTo("7개입니다.");
        assertThat(cleaned.get(1).getMetadata()).doesNotContainKey("anthropicThinkingContents");
    }

    @Test
    void 결과가_있는_tool_호출은_순서대로_보낸다() {
        List<Message> history = List.of(new SystemMessage("규칙"), new UserMessage("재고?"), assistant("", CALL), RESULT,
                new AssistantMessage("7개입니다."));

        List<Message> cleaned = ModelHistoryAdvisor.clean(history);

        assertThat(cleaned).hasSize(5);
        assertThat(((AssistantMessage) cleaned.get(2)).getToolCalls()).containsExactly(CALL);
        assertThat(cleaned.get(3)).isSameAs(RESULT);
    }

    @Test
    void 결과가_없는_tool_호출은_뺀다() {
        List<Message> cleaned = ModelHistoryAdvisor.clean(
                List.of(new UserMessage("재고?"), assistant("", CALL), new UserMessage("다시")));

        assertThat(cleaned).extracting(Message::getText).containsExactly("재고?", "다시");
    }

    @Test
    void 결과가_없는_tool_호출의_text는_남긴다() {
        List<Message> cleaned = ModelHistoryAdvisor.clean(
                List.of(new UserMessage("재고?"), assistant("확인해 보겠습니다.", CALL), new UserMessage("다시")));

        assertThat(cleaned).hasSize(3);
        assertThat(cleaned.get(1).getText()).isEqualTo("확인해 보겠습니다.");
        assertThat(((AssistantMessage) cleaned.get(1)).hasToolCalls()).isFalse();
    }

    @Test
    void 일부만_답한_tool_호출은_결과까지_함께_뺀다() {
        ToolCall other = new ToolCall("t2", "function", "getStock", "{}");

        List<Message> cleaned = ModelHistoryAdvisor.clean(
                List.of(new UserMessage("재고?"), assistant("", CALL, other), RESULT, new UserMessage("다시")));

        assertThat(cleaned).extracting(Message::getText).containsExactly("재고?", "다시");
    }

    @Test
    void 빈_답은_뺀다() {
        List<Message> cleaned = ModelHistoryAdvisor.clean(
                List.of(new UserMessage("재고?"), new AssistantMessage(""), new UserMessage("다시")));

        assertThat(cleaned).extracting(Message::getText).containsExactly("재고?", "다시");
    }

    @Test
    void ChatClient는_모델을_부르기_전에_history를_다듬는다() {
        ChatModel model = mock(ChatModel.class);
        given(model.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
        given(model.call(any(Prompt.class)))
                .willReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("네")))));
        ChatClient client = new ChatClientConfig().shopChatClient(ChatClient.builder(model),
                MessageWindowChatMemory.builder().build());
        Message answer = AssistantMessage.builder()
                .content("7개입니다.")
                .properties(Map.of("anthropicThinkingContents", List.of("block")))
                .build();

        client.prompt()
                .messages(new UserMessage("재고?"), answer)
                .user("고마워")
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, "user"))
                .call()
                .content();

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(sent.capture());
        assertThat(sent.getValue().getInstructions()).filteredOn(AssistantMessage.class::isInstance).singleElement()
                .satisfies(message -> assertThat(message.getMetadata()).doesNotContainKey("anthropicThinkingContents"));
    }

    @Test
    void 다듬은_prompt로_다음_advisor를_부른다() {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("재고?"), assistant("", CALL), new UserMessage("다시"))))
                .build();

        new ModelHistoryAdvisor().adviseStream(request, chain);

        ArgumentCaptor<ChatClientRequest> sent = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextStream(sent.capture());
        assertThat(sent.getValue().prompt().getInstructions()).extracting(Message::getText)
                .containsExactly("재고?", "다시");
    }
}
