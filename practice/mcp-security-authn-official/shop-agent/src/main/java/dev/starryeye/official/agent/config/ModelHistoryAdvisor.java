package dev.starryeye.official.agent.config;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 모델에게 보내기 직전에 thinking block을 뺀다.
 *
 * <p>Claude의 thinking block은 앞선 대화가 만들어질 때와 똑같아야 다시 보낼 수 있다.
 * 그런데 Spring AI는 tool loop에서 다시 보낼 때 thinking block을 text 뒤로 옮긴다.
 * thinking을 끌 수 없는 모델도 있어서, thinking block을 다시 보내지 않는다.
 */
public class ModelHistoryAdvisor implements CallAdvisor, StreamAdvisor {

    /** 모델을 부르는 마지막 advisor 바로 앞이다. */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1;

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(cleaned(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(cleaned(request));
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private static ChatClientRequest cleaned(ChatClientRequest request) {
        Prompt prompt = request.prompt().mutate().messages(clean(request.prompt().getInstructions())).build();
        return request.mutate().prompt(prompt).build();
    }

    static List<Message> clean(List<Message> messages) {
        return messages.stream().map(ModelHistoryAdvisor::withoutThinking).toList();
    }

    private static Message withoutThinking(Message message) {
        if (message instanceof AssistantMessage assistant) {
            return AssistantMessage.builder()
                    .content(assistant.getText())
                    .toolCalls(assistant.getToolCalls())
                    .build();
        }
        return message;
    }
}
