package dev.starryeye.memoryauthn.authserver;

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
	 * oauth2Login 은 openid 스코프로 id_token 을 받는다. 그 흐름이 성립하려면
	 * OIDC 디스커버리 문서가 있어야 한다 — AuthorizationServerConfig 가 oidc() 를 켠다.
	 */
	@Test
	void OIDC_메타데이터를_공개한다() throws Exception {
		mockMvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.issuer").value("http://localhost:9020"));
	}

	@Test
	void memory_agent_클라이언트가_등록되어_있다() {
		var client = registeredClientRepository.findByClientId("memory-agent");

		assertThat(client).isNotNull();
		assertThat(client.getRedirectUris())
				.containsExactly("http://localhost:8130/login/oauth2/code/authserver");
	}

	@Test
	void 로그인_화면이_제공된다() throws Exception {
		mockMvc.perform(get("/login"))
				.andExpect(status().isOk());
	}
}
