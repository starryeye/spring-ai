package dev.starryeye.productagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 에이전트가 사용할 {@link ChatClient} 를 조립한다.
 * <p>
 * 여기서 하는 일은 두 가지 — 시스템 프롬프트를 심고, MCP 툴을 붙이는 것이다.
 * 이걸 컨트롤러가 아니라 설정으로 분리한 이유는 컨트롤러의 책임을 요청/응답 처리로
 * 좁히고, 조립된 {@code ChatClient} 를 그대로 주입해 테스트할 수 있게 하기 위해서다.
 * <p>
 * 주의 — 이 클래스는 <b>여러 {@code ChatModel} 중 무엇을 쓸지 고르지 않는다.</b>
 * 그 선택은 {@code spring.ai.model.chat} 속성과 Spring 프로파일이 담당하며,
 * 활성 {@code ChatModel} 빈이 항상 하나뿐이기 때문에 자동설정된
 * {@link ChatClient.Builder} 를 그대로 받아 쓸 수 있다.
 * {@code @Qualifier} 나 {@code @Primary} 로 모호성을 푸는 코드는 여기에 없어야 한다.
 */
@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            당신은 상품 재고를 안내하는 도우미입니다.
            상품이나 재고에 대한 질문에는 반드시 제공된 도구로 실제 데이터를 조회한 뒤 답하세요.
            추측하지 말고, 조회 결과에 없는 내용은 모른다고 답하세요.
            """;

    /**
     * MCP 클라이언트 자동설정은 {@link ToolCallbackProvider} 빈을 만들어줄 뿐,
     * 그 툴들을 모델에 알려주지는 않는다. 여기서 직접 꽂아야 LLM이 툴 정의를 보게 된다.
     * 이 한 줄이 빠지면 기동도 되고 답변도 오지만 모델은 툴 없이 기억으로만 답한다.
     * <p>
     * {@link ObjectProvider} 로 받는 이유는 {@code spring.ai.mcp.client.enabled=false} 인
     * 환경(테스트 등)에서는 그 빈이 아예 없기 때문이다 — 툴 없이도 기동은 되어야 한다.
     * <p>
     * Spring AI 2.0 에서 {@code defaultToolCallbacks(...)} 는 제거 예정으로 표시되었으므로
     * {@code defaultTools(Object...)} 를 쓴다. 이 메서드는 인자가 {@code ToolCallbackProvider}
     * 이면 provider 목록에, {@code ToolCallback} 이면 콜백 목록에 넣는다.
     */
    @Bean
    public ChatClient productChatClient(ChatClient.Builder builder,
                                        ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        ChatClient.Builder configured = builder.defaultSystem(SYSTEM_PROMPT);
        toolCallbackProvider.ifAvailable(configured::defaultTools);
        return configured.build();
    }
}
