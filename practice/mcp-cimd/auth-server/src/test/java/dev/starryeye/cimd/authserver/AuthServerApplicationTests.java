package dev.starryeye.cimd.authserver;

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
	 * oauth2Login은 openid scope로 id_token을 받는다. 그 흐름이 성립하려면
	 * OIDC discovery 문서가 있어야 한다. AuthorizationServerConfig가 oidc()를 켠다.
	 */
	@Test
	void OIDC_메타데이터를_공개한다() throws Exception {
		this.mockMvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.issuer").value("http://localhost:9060"));
	}

	@Test
	void 미리_등록한_client가_없다() {
		// URL이 아닌 client_id는 문서를 가져오지도 않는다.
		assertThat(this.registeredClientRepository.findByClientId("cimd-shop-agent")).isNull();
	}

	@Test
	void 로그인_화면이_제공된다() throws Exception {
		this.mockMvc.perform(get("/login"))
				.andExpect(status().isOk());
	}
}
