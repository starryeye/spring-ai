package dev.starryeye.officialauthserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthServerApplicationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	RegisteredClientRepository registeredClientRepository;

	@Test
	void contextLoads() {
	}

	/**
	 * community 버전은 이 엔드포인트를 살리려고 OidcDiscoveryConfig 라는 파일을
	 * 따로 만들어야 했다. 공식 자동설정은 그냥 준다 — 그것을 확인하는 테스트다.
	 */
	@Test
	void OIDC_메타데이터를_추가_설정_없이_공개한다() throws Exception {
		mockMvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.issuer").value("http://localhost:9010"));
	}

	@Test
	void official_shop_agent_클라이언트가_등록되어_있다() {
		var client = registeredClientRepository.findByClientId("official-shop-agent");

		assertThat(client).isNotNull();
		assertThat(client.getRedirectUris())
				.containsExactly("http://localhost:8110/login/oauth2/code/authserver");
	}

	@Test
	void 로그인_화면이_제공된다() throws Exception {
		mockMvc.perform(get("/login"))
				.andExpect(status().isOk());
	}
}
