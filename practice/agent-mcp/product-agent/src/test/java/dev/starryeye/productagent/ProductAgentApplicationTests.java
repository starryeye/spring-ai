package dev.starryeye.productagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.ai.openai.api-key=test-key"
})
@ActiveProfiles("openai")
class ProductAgentApplicationTests {

    @Autowired
    ChatClient.Builder chatClientBuilder;

    @Test
    void 컨텍스트가_기동되고_ChatClient_빌더가_하나만_주입된다() {
        assertThat(chatClientBuilder).isNotNull();
        assertThat(chatClientBuilder.build()).isNotNull();
    }
}
