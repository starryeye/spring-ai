package dev.starryeye.officialagent;

import io.modelcontextprotocol.client.McpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopAgentApplicationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ClientRegistrationRepository clientRegistrationRepository;

	@Autowired
	OAuth2ClientProperties oAuth2ClientProperties;

	@Autowired
	OAuth2AuthorizedClientManager authorizedClientManager;

	@Autowired
	ApplicationContext applicationContext;

	@Test
	void contextLoads() {
	}

	@Test
	void 로그인하지_않으면_인가_엔드포인트로_보낸다() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrlPattern("/oauth2/authorization/**"));
	}

	@Test
	void OAuth2_클라이언트_등록이_정확히_하나다() {
		var registration = clientRegistrationRepository.findByRegistrationId("authserver");

		assertThat(registration).isNotNull();
		assertThat(registration.getClientId()).isEqualTo("official-shop-agent");
		assertThat(oAuth2ClientProperties.getRegistration()).hasSize(1);
	}

	/**
	 * 서블릿 요청 없이 Authentication 만으로 토큰을 꺼낼 수 있어야
	 * 리액터 스레드에서 토큰을 붙일 수 있다. 매니저 타입이 바뀌면 그 성질이 깨진다.
	 */
	@Test
	void 서블릿_요청이_필요없는_인가_매니저를_쓴다() {
		assertThat(authorizedClientManager)
				.isInstanceOf(org.springframework.security.oauth2.client
						.AuthorizedClientServiceOAuth2AuthorizedClientManager.class);
	}

	@Test
	void MCP_클라이언트에_인증_커스터마이저가_꽂힌다() {
		assertThat(applicationContext.getBeansOfType(
				org.springframework.ai.mcp.customizer.McpClientCustomizer.class))
				.hasSizeGreaterThanOrEqualTo(2);
	}
}
