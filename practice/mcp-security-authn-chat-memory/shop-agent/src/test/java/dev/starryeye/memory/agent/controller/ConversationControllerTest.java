package dev.starryeye.memory.agent.controller;

import dev.starryeye.memory.agent.config.SecurityConfig;
import dev.starryeye.memory.agent.domain.ConversationId;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장소 조회 경로는 LLM을 부르지 않으므로 통합 테스트로 확인할 수 있다.
 * 이 practice가 보이려는 것(사용자별 격리)을 여기서 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ChatMemory chatMemory;

	/**
	 * 이 Boot 버전({@code spring-boot-webmvc-test} 4.1.x)의 {@code @AutoConfigureMockMvc}는
	 * classpath에 Spring Security가 있어도 {@code SecurityMockMvcConfigurers.springSecurity()}를 붙이지 않는다.
	 * 예전 Boot 버전과 다른 점이다({@code SpringBootMockMvcBuilderCustomizer}의 소스와 bytecode에 security 관련 코드가 없다).
	 * 이 연결이 없으면 {@code SecurityContextHolderFilter}가 요청마다 저장소(session·요청 속성)에서 context를 다시 읽는다.
	 * 그러면 {@code @WithMockUser}가 thread에 넣어 둔 인증을 덮어써서, 모든 요청이 302로
	 * {@code /oauth2/authorization/authserver}에 redirect된다.
	 * 아래 customizer가 그 연결을 대신 붙인다. 테스트 메서드는 건드리지 않는다.
	 */
	@TestConfiguration
	static class SecurityMockMvcSupport {

		@Bean
		MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
			return builder -> builder.apply(SecurityMockMvcConfigurers.springSecurity());
		}
	}

	/**
	 * index.html의 흐름을 따른다. 페이지가 준 {@code XSRF-TOKEN} cookie를 되돌려 보내고,
	 * 그 값을 {@code X-XSRF-TOKEN} header에 넣는다.
	 * spring-security-test의 {@code csrf()}는 context의 {@code CsrfFilter} 저장소를 바꾼다.
	 * 그 변경이 같은 context를 쓰는 다른 테스트에 번지므로 쓰지 않는다({@code ChatControllerTest}와 같은 방식).
	 */
	RequestPostProcessor 페이지의_CSRF_토큰() throws Exception {
		Cookie token = mockMvc.perform(get("/index.html"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getCookie("XSRF-TOKEN");
		org.assertj.core.api.Assertions.assertThat(token).isNotNull();
		return request -> {
			request.setCookies(token);
			request.addHeader("X-XSRF-TOKEN", token.getValue());
			return request;
		};
	}

	@BeforeEach
	void seed() {
		chatMemory.clear("alice:default");
		chatMemory.clear("bob:default");
		chatMemory.add("alice:default", List.of(
				new UserMessage("내 이름은 앨리스야"),
				new AssistantMessage("반가워요 앨리스님")));
		// bob의 namespace에도 대화를 넣어 둔다. 이것이 없으면 아래 테스트는 ConversationId.of가
		// Authentication을 아예 무시해도(곧 격리가 전혀 없어도) 통과해 버린다.
		// bob의 key에 넣은 데이터가 없어서 "격리됨"과 "그냥 없음"을 구분하지 못하기 때문이다.
		chatMemory.add("bob:default", List.of(
				new UserMessage("내 이름은 밥이야"),
				new AssistantMessage("반가워요 밥님")));
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

	/**
	 * 이 practice의 핵심이다. bob이 같은 label("default")로 조회해도 alice의 대화가 아니라
	 * 자기 대화를 얻는다.
	 * bob의 namespace에도 데이터를 넣어 두는 이유가 있다. 넣어 두지 않으면 {@code ConversationId.of}가
	 * {@code Authentication}을 완전히 무시해도(격리가 전혀 없어도) 이 테스트가 통과한다.
	 * bob의 key가 처음부터 비어 있으니 "격리됨"과 "그냥 없음"을 구분할 수 없다.
	 */
	@Test
	@WithMockUser(username = "bob")
	void 남의_대화는_같은_label_로도_안_읽힌다() throws Exception {
		mockMvc.perform(get("/api/conversations/default"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].text").value("내 이름은 밥이야"))
				.andExpect(jsonPath("$[*].text", everyItem(not(containsString("앨리스")))));
	}

	/** 경로에 남의 전체 ID를 밀어 넣어도 서버가 자기 namespace를 붙여 찾는다. */
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
				.andExpect(jsonPath("$", hasItem("alice:default")));
	}

	/** 사용자 이름에 구분자({@code :})가 든 다른 사용자의 대화는 접두사 거르기를 통과하지 못한다. */
	@Test
	@WithMockUser(username = "alice")
	void 사용자_이름에_구분자가_든_다른_사용자의_대화는_목록에_보이지_않는다() throws Exception {
		String other = ConversationId.of(new UsernamePasswordAuthenticationToken("alice:x", "n/a", List.of()), "default");
		chatMemory.add(other, List.of(new UserMessage("나는 alice:x 야")));
		try {
			mockMvc.perform(get("/api/conversations"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$", hasItem("alice:default")))
					.andExpect(jsonPath("$", not(hasItem(other))));
		}
		finally {
			chatMemory.clear(other);
		}
	}

	/**
	 * {@link SecurityConfig}는 이 practice 전체에서 CSRF 예외를 하나도 두지 않는다
	 * (official·community practice도 같다).
	 * 그 결정을 코드 리뷰만으로 지키기는 어렵다.
	 * 누군가 "이 endpoint만" 하며 예외를 다시 넣어도 아무 테스트도 실패하지 않는다.
	 * 이 테스트가 그런 회귀를 잡는다.
	 */
	@Test
	@WithMockUser(username = "alice")
	void CSRF_토큰_없이_지우면_403() throws Exception {
		mockMvc.perform(delete("/api/conversations/default"))
				.andExpect(status().isForbidden());
	}

	/** 위 테스트의 대조군이다. 페이지가 준 CSRF token을 넣어 보내면 같은 요청이 정상으로 통과한다. */
	@Test
	@WithMockUser(username = "alice")
	void CSRF_토큰과_함께_지우면_204() throws Exception {
		mockMvc.perform(delete("/api/conversations/default").with(페이지의_CSRF_토큰()))
				.andExpect(status().isNoContent());
	}

	/**
	 * DELETE의 격리를, 조회(GET) 경로와 {@code ConversationId.of}를 함께 쓴다는 사실에 기대지 않고 직접 확인한다.
	 * bob이 경로에 alice의 전체 ID를 밀어 넣어 지워도, alice의 실제 대화({@code alice:default})가
	 * 그대로 남아 있는지 저장소를 직접 열어 본다.
	 * 204만 보고 끝내면 "격리되어 남의 것을 못 지웠다"와 "엉뚱한 것을 지웠다"를 구분할 수 없다.
	 * 이 검증이 그 둘을 가른다.
	 */
	@Test
	@WithMockUser(username = "bob")
	void bob_이_alice_ID_로_지워도_alice_대화는_그대로_남는다() throws Exception {
		mockMvc.perform(delete("/api/conversations/alice:default").with(페이지의_CSRF_토큰()))
				.andExpect(status().isNoContent());

		List<Message> aliceMessages = chatMemory.get("alice:default");
		assertThat(aliceMessages, hasSize(2));
		assertThat(aliceMessages.get(0).getText(), is("내 이름은 앨리스야"));
	}
}
