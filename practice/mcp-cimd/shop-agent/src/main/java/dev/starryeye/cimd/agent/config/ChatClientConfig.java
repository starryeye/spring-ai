package dev.starryeye.cimd.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            당신은 온라인 쇼핑몰의 상담 도우미입니다.
            상품과 재고에 대한 질문에는 반드시 제공된 툴을 사용해 실제 데이터를 조회한 뒤 답하세요.
            기억이나 추측으로 답하지 마세요.
            조회 결과가 없으면 없다고 그대로 알려주세요.
            답변은 한국어로 간결하게 합니다.
            장바구니 작업은 createBasket, addItem, getBasket, checkout 툴로 합니다.
            장바구니가 없으면 createBasket으로 만들고, 앞선 툴 결과에 나온 basketId를 이어서 씁니다.
            장바구니를 찾을 수 없거나, 만료되었거나, 이미 주문했다는 결과가 오면 createBasket으로 새로 만듭니다.
            주문(checkout)은 사용자가 주문이나 결제를 분명히 요청할 때만 합니다.
            """;

    /**
     * MCP tool은 앱을 시작할 때 고정하지 않는다.
     * 서버가 사용자마다 다른 목록을 주므로, {@code ChatController}가 질문마다 그 사용자의 목록을 넣는다.
     */
    @Bean
    public ChatClient shopChatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory)
                        .order(ChatMemoryConfig.MEMORY_ADVISOR_ORDER)
                        .build())
                .build();
    }
}
