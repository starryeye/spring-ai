package dev.starryeye.memoryauthn.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장소 조회 경로는 LLM 을 부르지 않으므로 통합 테스트가 가능하다.
 * 이 practice 의 주장(사용자별 격리)이 여기서 검증된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ChatMemory chatMemory;

	/**
	 * 이 Boot 버전({@code spring-boot-webmvc-test} 4.1.x)의 {@code @AutoConfigureMockMvc} 는
	 * Spring Security 를 classpath 로 자동 감지해 {@code SecurityMockMvcConfigurers.springSecurity()}
	 * 를 붙여주지 않는다(과거 Boot 버전과 다른 점 — {@code SpringBootMockMvcBuilderCustomizer} 소스에
	 * security 관련 코드가 전혀 없음을 바이트코드/소스로 직접 확인했다). 이 배선이 없으면
	 * {@code SecurityContextHolderFilter} 가 요청마다 저장소(세션/요청 속성)에서 컨텍스트를 다시
	 * 읽어 {@code @WithMockUser} 가 스레드에 심어둔 인증을 덮어써 버려서, 모든 요청이 302 로
	 * {@code /oauth2/authorization/authserver} 에 리다이렉트된다. 아래 커스터마이저가 그 배선을
	 * 대신 붙인다 — 테스트 메서드 자체는 건드리지 않는다.
	 */
	@TestConfiguration
	static class SecurityMockMvcSupport {

		@Bean
		MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
			return builder -> builder.apply(SecurityMockMvcConfigurers.springSecurity());
		}
	}

	@BeforeEach
	void seed() {
		chatMemory.clear("alice:default");
		chatMemory.clear("bob:default");
		chatMemory.add("alice:default", List.of(
				new UserMessage("내 이름은 앨리스야"),
				new AssistantMessage("반가워요 앨리스님")));
	}

	@Test
	@WithMockUser(username = "alice")
	void 자기_대화는_읽힌다() throws Exception {
		mockMvc.perform(get("/api/conversations/default"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].role").value("user"))
				.andExpect(jsonPath("$[0].text").value("내 이름은 앨리스야"));
	}

	/** 이 practice 의 핵심. bob 은 같은 label 로도 alice 의 것을 못 읽는다. */
	@Test
	@WithMockUser(username = "bob")
	void 남의_대화는_같은_label_로도_안_읽힌다() throws Exception {
		mockMvc.perform(get("/api/conversations/default"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	/** 경로에 남의 전체 ID 를 밀어넣어도 자기 네임스페이스로 강제된다. */
	@Test
	@WithMockUser(username = "bob")
	void 경로에_남의_ID_를_넣어도_소용없다() throws Exception {
		mockMvc.perform(get("/api/conversations/alice:default"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@WithMockUser(username = "bob")
	void 목록에_남의_대화_ID_가_보이지_않는다() throws Exception {
		mockMvc.perform(get("/api/conversations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", everyItem(startsWith("bob:"))));
	}

	@Test
	@WithMockUser(username = "alice")
	void 목록에_자기_대화가_보인다() throws Exception {
		mockMvc.perform(get("/api/conversations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("alice:default")));
	}
}
