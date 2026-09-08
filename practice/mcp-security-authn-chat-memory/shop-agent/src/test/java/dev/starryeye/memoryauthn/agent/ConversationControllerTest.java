package dev.starryeye.memoryauthn.agent;

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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
		// bob 의 네임스페이스에도 대화를 심어둔다. 이게 없으면 아래 테스트는 ConversationId.of 가
		// Authentication 을 아예 무시해도(즉 격리가 전혀 안 돼도) 통과해 버린다 — bob 이 파생한
		// 키가 애초에 시드된 적이 없어 "격리됨"과 "그냥 없음"을 구분하지 못하기 때문이다.
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
	 * 이 practice 의 핵심. bob 이 같은 label("default")로 조회해도 alice 의 대화가 아니라
	 * 자기 자신의 대화를 얻는다. bob 의 네임스페이스에도 데이터를 심어두는 이유는, 심어두지
	 * 않으면 이 테스트가 {@code ConversationId.of} 가 {@code Authentication} 을 완전히
	 * 무시해도(격리가 전혀 없어도) 통과해버리기 때문이다 — bob 이 파생한 키가 애초에 비어
	 * 있으니 "격리됨"과 "단순히 없음"을 구분할 수 없다.
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
				.andExpect(jsonPath("$", hasItem("alice:default")));
	}

	/**
	 * {@link SecurityConfig} 는 이 practice 전체에서 CSRF 예외를 단 하나도 두지 않는다
	 * (두 부모 practice 는 {@code /api/chat} 을 예외로 뒀다). 그 결정을 코드 리뷰만으로
	 * 지키는 것은 취약하다 — 누군가 나중에 "귀찮으니 이 엔드포인트만" 하며 예외를 다시
	 * 넣어도 아무 테스트도 실패하지 않을 것이다. 이 테스트가 그 회귀를 잡는다.
	 */
	@Test
	@WithMockUser(username = "alice")
	void CSRF_토큰_없이_지우면_403() throws Exception {
		mockMvc.perform(delete("/api/conversations/default"))
				.andExpect(status().isForbidden());
	}

	/** 위 테스트의 대조군 — 토큰을 실어 보내면 같은 요청이 정상적으로 통과한다. */
	@Test
	@WithMockUser(username = "alice")
	void CSRF_토큰과_함께_지우면_204() throws Exception {
		mockMvc.perform(delete("/api/conversations/default").with(csrf()))
				.andExpect(status().isNoContent());
	}

	/**
	 * DELETE 의 격리는 지금까지 조회(GET) 경로와 {@code ConversationId.of} 를 공유한다는
	 * 사실에만 기대고 있었다 — 직접 겨냥한 증거가 없었다. 여기서는 bob 이 경로에 alice 의
	 * 전체 ID 를 밀어넣어 지워도, alice 의 실제 대화({@code alice:default})가 그대로
	 * 남아있는지 저장소를 직접 열어 확인한다. 204 만 보고 끝내면 "격리돼서 남의 걸 못
	 * 지웠다"와 "엉뚱한 걸 지워버렸다"를 구분할 수 없다 — 이 검증이 그 둘을 가른다.
	 */
	@Test
	@WithMockUser(username = "bob")
	void bob_이_alice_ID_로_지워도_alice_대화는_그대로_남는다() throws Exception {
		mockMvc.perform(delete("/api/conversations/alice:default").with(csrf()))
				.andExpect(status().isNoContent());

		List<Message> aliceMessages = chatMemory.get("alice:default");
		assertThat(aliceMessages, hasSize(2));
		assertThat(aliceMessages.get(0).getText(), is("내 이름은 앨리스야"));
	}
}
