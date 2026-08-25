package dev.starryeye.shopagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM 실호출은 테스트하지 않는다. 배선만 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShopAgentApplicationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ClientRegistrationRepository clientRegistrationRepository;

	@Test
	void contextLoads() {
	}

	@Test
	void 로그인하지_않으면_로그인으로_보낸다() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().is3xxRedirection());
	}

	/**
	 * 등록이 정확히 하나여야 transport 커스터마이저가 토큰을 붙인다.
	 * 둘 이상이면 WARN 한 줄 남기고 조용히 아무 것도 하지 않는다.
	 */
	@Test
	void OAuth2_클라이언트_등록이_정확히_하나다() {
		var registration = clientRegistrationRepository.findByRegistrationId("authserver");

		assertThat(registration).isNotNull();
		assertThat(registration.getClientId()).isEqualTo("shop-agent");
		assertThat(registration.getAuthorizationGrantType().getValue())
				.isEqualTo("authorization_code");
	}
}
