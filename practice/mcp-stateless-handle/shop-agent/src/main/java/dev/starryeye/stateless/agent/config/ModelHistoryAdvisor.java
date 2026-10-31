package dev.starryeye.stateless.agent.config;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 모델에게 보내기 직전에 history를 Claude API가 받는 모양으로 다듬는다.
 * 대화 기억은 그대로 두고 보내는 메시지만 바꾼다.
 *
 * <p>Claude의 thinking block은 앞선 대화가 만들어질 때와 똑같아야 다시 보낼 수 있다.
 * 그런데 Spring AI는 다시 보낼 때 thinking block을 text 뒤로 옮기고, 대화 기억의 창은 오래된 메시지를 지운다.
 * thinking을 끌 수 없는 모델도 있어서, thinking block을 다시 보내지 않는다.
 *
 * <p>Claude API는 tool 호출 바로 뒤에 그 결과가 없는 history와 빈 답도 받지 않는다.
 * turn이 tool 실행 중에 오류나 연결 끊김으로 끝나면 결과 없는 tool 호출이 기억에 남는다.
 * 이런 메시지가 남아도 다음 질문이 실패하지 않도록 보내기 전에 뺀다.
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
        List<Message> cleaned = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            // tool 결과는 아래에서 tool 호출과 함께 넣는다.
            // 여기까지 온 tool 결과는 짝이 없는 것이다.
            if (message instanceof ToolResponseMessage) {
                continue;
            }
            if (!(message instanceof AssistantMessage assistant)) {
                cleaned.add(message);
                continue;
            }
            Message next = i + 1 < messages.size() ? messages.get(i + 1) : null;
            if (assistant.hasToolCalls() && answered(assistant, next)) {
                cleaned.add(AssistantMessage.builder()
                        .content(assistant.getText())
                        .toolCalls(assistant.getToolCalls())
                        .build());
                cleaned.add(next);
                i++;
            }
            else if (StringUtils.hasText(assistant.getText())) {
                cleaned.add(new AssistantMessage(assistant.getText()));
            }
        }
        return cleaned;
    }

    private static boolean answered(AssistantMessage assistant, Message next) {
        if (!(next instanceof ToolResponseMessage results)) {
            return false;
        }
        Set<String> ids = results.getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::id)
                .collect(Collectors.toSet());
        return assistant.getToolCalls().stream().allMatch(call -> ids.contains(call.id()));
    }
}
