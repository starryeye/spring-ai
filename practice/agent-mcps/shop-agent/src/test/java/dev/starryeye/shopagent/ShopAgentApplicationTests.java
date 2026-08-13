package dev.starryeye.shopagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
		"spring.ai.mcp.client.enabled=false",
		"spring.ai.openai.api-key=test-key"
})
@ActiveProfiles("openai")
class ShopAgentApplicationTests {

	@Test
	void contextLoads() {
	}

}
