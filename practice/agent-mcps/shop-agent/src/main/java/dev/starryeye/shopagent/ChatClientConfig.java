package dev.starryeye.shopagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 서버가 둘이어도 이 코드는 그대로다.
 * 자동설정은 클라이언트 수와 무관하게 {@link ToolCallbackProvider} 빈을 <b>하나</b>만 만들고,
 * 그 안에 모든 서버의 툴을 합쳐 담는다.
 */
@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            당신은 온라인 쇼핑몰의 상담 도우미입니다.
            상품·재고·주문에 대한 질문에는 반드시 제공된 도구로 실제 데이터를 조회한 뒤 답하세요.
            추측하지 말고, 조회 결과에 없는 내용은 모른다고 답하세요.
            주문한 상품의 재고를 물으면 먼저 주문을 조회해 상품 ID를 얻고, 그 ID로 재고를 조회하세요.
            """;

    @Bean
    public ChatClient shopChatClient(ChatClient.Builder builder,
                                     ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        ChatClient.Builder configured = builder.defaultSystem(SYSTEM_PROMPT);
        toolCallbackProvider.ifAvailable(configured::defaultTools);
        return configured.build();
    }
}
