package dev.starryeye.officialagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
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
            """;

    /**
     * MCP 툴은 자동으로 모델에 전달되지 않는다. MCP 클라이언트 자동설정이 만들어 주는 것은
     * {@link ToolCallbackProvider} 빈까지이고, {@code defaultTools(...)} 로 직접 꽂아야
     * LLM 이 툴 정의를 받는다. 이 줄을 지우면 기동도 되고 답변도 오지만 —
     * 모델은 툴 없이 기억으로만 답한다.
     */
    @Bean
    public ChatClient shopChatClient(ChatClient.Builder builder,
                                     ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        ChatClient.Builder configured = builder.defaultSystem(SYSTEM_PROMPT);
        toolCallbackProvider.ifAvailable(configured::defaultTools);
        return configured.build();
    }
}
