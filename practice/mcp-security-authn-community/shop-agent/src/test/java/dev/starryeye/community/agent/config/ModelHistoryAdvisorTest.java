package dev.starryeye.community.agent.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModelHistoryAdvisorTest {

    private static final ToolCall CALL = new ToolCall("t1", "function", "getStock", "{}");

    private static final Map<String, Object> THINKING = Map.of("anthropicThinkingContents", List.of("block"));

    private static AssistantMessage withThinking() {
        return AssistantMessage.builder()
                .content("확인해 보겠습니다.")
                .toolCalls(List.of(CALL))
                .properties(THINKING)
                .build();
    }

    @Test
    void metadata의_thinking_block만_떼어_내고_text와_tool_호출은_남긴다() {
        ToolResponseMessage result = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponse("t1", "getStock", "7개")))
                .build();

        List<Message> cleaned = ModelHistoryAdvisor.clean(List.of(new UserMessage("재고?"), withThinking(), result));

        assertThat(cleaned).hasSize(3);
        assertThat(cleaned.get(1).getText()).isEqualTo("확인해 보겠습니다.");
        assertThat(cleaned.get(1).getMetadata()).doesNotContainKey("anthropicThinkingContents");
        assertThat(((AssistantMessage) cleaned.get(1)).getToolCalls()).containsExactly(CALL);
        assertThat(cleaned.get(2)).isSameAs(result);
    }

    @Test
    void ChatClient는_모델을_부르기_전에_thinking_block을_뺀다() {
        ChatModel model = mock(ChatModel.class);
        given(model.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
        given(model.call(any(Prompt.class)))
                .willReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("네")))));
        ChatClient client = new ChatClientConfig().shopChatClient(ChatClient.builder(model),
                new DefaultListableBeanFactory().getBeanProvider(ToolCallbackProvider.class));
        Message answer = AssistantMessage.builder().content("7개입니다.").properties(THINKING).build();

        client.prompt().messages(new UserMessage("재고?"), answer).user("고마워").call().content();

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(sent.capture());
        assertThat(sent.getValue().getInstructions()).filteredOn(AssistantMessage.class::isInstance).singleElement()
                .satisfies(message -> assertThat(message.getMetadata()).doesNotContainKey("anthropicThinkingContents"));
    }

    @Test
    void 다듬은_prompt로_다음_advisor를_부른다() {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("재고?"), withThinking())))
                .build();

        new ModelHistoryAdvisor().adviseStream(request, chain);

        ArgumentCaptor<ChatClientRequest> sent = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextStream(sent.capture());
        assertThat(sent.getValue().prompt().getInstructions().get(1).getMetadata())
                .doesNotContainKey("anthropicThinkingContents");
    }
}
