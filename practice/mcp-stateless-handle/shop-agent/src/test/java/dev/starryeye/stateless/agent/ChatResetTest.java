package dev.starryeye.stateless.agent;

import dev.starryeye.stateless.agent.discovery.DiscoveryFixtures;
import dev.starryeye.stateless.agent.discovery.McpAuthorizationDiscovery;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 화면의 "새 대화" 버튼이 부르는 {@code /api/chat/reset}을 본다. CSRF 검사를 받고, 로그인 사용자의 기억만 지운다. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatResetTest {

	/** Boot 4.1의 {@code @AutoConfigureMockMvc}는 springSecurity()를 붙이지 않는다. {@code @WithMockUser}에 필요하다. */
	@TestConfiguration
	static class SecurityMockMvcSupport {

		@Bean
		MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
			return builder -> builder.apply(SecurityMockMvcConfigurers.springSecurity());
		}
	}

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
	ToolCallbackProvider toolCallbackProvider;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ChatMemory chatMemory;

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[0]);
	}

	@Test
	@WithMockUser("reset-user")
	void 새_대화는_로그인_사용자의_기억을_지운다() throws Exception {
		this.chatMemory.add("reset-user", List.of(new UserMessage("장바구니 만들어 줘")));
		this.chatMemory.add("reset-bystander", List.of(new UserMessage("다른 사용자의 질문")));
		Cookie token = this.mockMvc.perform(get("/index.html")).andReturn().getResponse().getCookie("XSRF-TOKEN");

		this.mockMvc.perform(post("/api/chat/reset").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
				.andExpect(status().isNoContent());

		assertThat(this.chatMemory.get("reset-user")).isEmpty();
		assertThat(this.chatMemory.get("reset-bystander")).hasSize(1);
	}

	@Test
	@WithMockUser("reset-other")
	void CSRF_토큰_없이_새_대화를_누르면_403이고_기억은_그대로다() throws Exception {
		this.chatMemory.add("reset-other", List.of(new UserMessage("장바구니 만들어 줘")));

		this.mockMvc.perform(post("/api/chat/reset")).andExpect(status().isForbidden());

		assertThat(this.chatMemory.get("reset-other")).hasSize(1);
	}
}
