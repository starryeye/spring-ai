package dev.starryeye.memory.agent.controller;

import dev.starryeye.memory.agent.mcp.UserMcpClients;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 채팅 요청의 연결을 확인한다. 사용자의 conversationId로 기억하고, 그 사용자의 MCP tool을 넘기고, logout하면 client를 닫는다. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

	@TestConfiguration
	static class SecurityMockMvcSupport {

		@Bean
		MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
			return builder -> builder.apply(SecurityMockMvcConfigurers.springSecurity());
		}
	}

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean
	UserMcpClients userMcpClients;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ChatMemory chatMemory;

	UserMcpClients.Lease lease = mock(UserMcpClients.Lease.class);

	@BeforeEach
	void setUp() {
		this.chatMemory.clear("alice:default");
		ToolCallbackProvider noTools = () -> new ToolCallback[0];
		given(this.lease.tools()).willReturn(noTools);
		given(this.userMcpClients.acquire(any())).willReturn(this.lease);
		// ChatClient가 Prompt를 만들며 chatModel.getOptions()를 부른다. mock의 기본 답은 null이라 NPE가 난다.
		given(this.chatModel.getOptions()).willReturn(ChatOptions.builder().build());
		given(this.chatModel.stream(any(Prompt.class))).willReturn(
				Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("재고는 3개입니다"))))));
	}

	/**
	 * index.html의 흐름을 따른다. 페이지가 준 {@code XSRF-TOKEN} cookie를 되돌려 보내고,
	 * 그 값을 {@code X-XSRF-TOKEN} header에 넣는다.
	 * spring-security-test의 {@code csrf()}는 context의 {@code CsrfFilter} 저장소를 바꾼다.
	 * 그 변경이 같은 context를 쓰는 다른 테스트에 번지므로 쓰지 않는다.
	 */
	RequestPostProcessor 페이지의_CSRF_토큰() throws Exception {
		Cookie token = this.mockMvc.perform(get("/index.html"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getCookie("XSRF-TOKEN");
		assertThat(token).isNotNull();
		return request -> {
			request.setCookies(token);
			request.addHeader("X-XSRF-TOKEN", token.getValue());
			return request;
		};
	}

	String 채팅(String message) throws Exception {
		MvcResult started = this.mockMvc.perform(post("/api/chat").param("label", "default").content(message)
						.with(페이지의_CSRF_토큰()))
				.andExpect(request().asyncStarted())
				.andReturn();
		return this.mockMvc.perform(asyncDispatch(started))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	@Test
	@WithMockUser(username = "alice")
	void 대화는_로그인한_사용자의_conversationId_로_기억된다() throws Exception {
		assertThat(채팅("노트북 재고 있어?")).isEqualTo("재고는 3개입니다");

		assertThat(this.chatMemory.get("alice:default")).extracting(Message::getText)
				.containsExactly("노트북 재고 있어?", "재고는 3개입니다");
	}

	@Test
	@WithMockUser(username = "alice")
	void 요청한_사용자의_MCP_tool_을_넘긴다() throws Exception {
		채팅("노트북 재고 있어?");

		then(this.userMcpClients).should().acquire(argThat(user -> "alice".equals(user.getName())));
	}

	/** stream이 끝나야 client를 돌려준다. 돌려주지 않으면 logout해도 그 client는 닫히지 않는다. */
	@Test
	@WithMockUser(username = "alice")
	void 채팅_stream_이_끝나면_빌린_MCP_client_를_돌려준다() throws Exception {
		MvcResult started = this.mockMvc.perform(post("/api/chat").param("label", "default").content("노트북 재고 있어?")
						.with(페이지의_CSRF_토큰()))
				.andExpect(request().asyncStarted())
				.andReturn();
		this.mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());

		then(this.lease).should().close();
	}

	/** {@code /api/chat}도 CSRF 예외가 없다. CSRF token 없이 보내면 controller까지 가지 않는다. */
	@Test
	@WithMockUser(username = "alice")
	void CSRF_토큰_없이_채팅하면_403() throws Exception {
		this.mockMvc.perform(post("/api/chat").param("label", "default").content("안녕"))
				.andExpect(status().isForbidden());

		then(this.userMcpClients).shouldHaveNoInteractions();
	}

	@Test
	@WithMockUser(username = "alice")
	void 로그아웃하면_그_사용자의_MCP_client_를_닫는다() throws Exception {
		this.mockMvc.perform(post("/logout").with(페이지의_CSRF_토큰()))
				.andExpect(status().is3xxRedirection());

		then(this.userMcpClients).should().close("alice");
	}
}
