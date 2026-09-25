package dev.starryeye.memoryauthn.agent;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 채팅 요청의 배선: 사용자의 conversationId 로 기억하고, 그 사용자의 MCP tool 을 넘기고, 로그아웃이 client 를 닫는다. */
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

	@BeforeEach
	void setUp() {
		this.chatMemory.clear("alice:default");
		ToolCallbackProvider noTools = () -> new ToolCallback[0];
		given(this.userMcpClients.toolsFor(any())).willReturn(noTools);
		// ChatClient 가 Prompt 를 만들며 chatModel.getOptions() 를 부른다 — 기본 답변은 null 이라 NPE 가 난다.
		given(this.chatModel.getOptions()).willReturn(ChatOptions.builder().build());
		given(this.chatModel.stream(any(Prompt.class))).willReturn(
				Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("재고는 3개입니다"))))));
	}

	/**
	 * index.html 의 흐름: 페이지가 준 {@code XSRF-TOKEN} 쿠키를 되돌려 보내고 그 값을 {@code X-XSRF-TOKEN}
	 * 헤더에 싣는다. spring-security-test 의 {@code csrf()} 는 context 의 {@code CsrfFilter} 저장소를 바꿔
	 * 같은 context 를 쓰는 다른 테스트에 번지므로 쓰지 않는다.
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

		then(this.userMcpClients).should().toolsFor(argThat(user -> "alice".equals(user.getName())));
	}

	@Test
	@WithMockUser(username = "alice")
	void 로그아웃하면_그_사용자의_MCP_client_를_닫는다() throws Exception {
		this.mockMvc.perform(post("/logout").with(페이지의_CSRF_토큰()))
				.andExpect(status().is3xxRedirection());

		then(this.userMcpClients).should().close("alice");
	}
}
