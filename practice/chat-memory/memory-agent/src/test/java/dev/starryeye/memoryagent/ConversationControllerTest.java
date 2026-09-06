package dev.starryeye.memoryagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장소 조회·삭제 경로는 LLM 을 부르지 않으므로 통합 테스트가 가능하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ChatMemory chatMemory;

	@BeforeEach
	void seed() {
		chatMemory.clear("alpha");
		chatMemory.clear("beta");
		chatMemory.add("alpha", List.of(
				new UserMessage("내 이름은 스타리야"),
				new AssistantMessage("반가워요 스타리님")));
	}

	@Test
	void 대화_내용을_역할과_함께_보여준다() throws Exception {
		mockMvc.perform(get("/api/conversations/alpha"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].role").value("user"))
				.andExpect(jsonPath("$[0].text").value("내 이름은 스타리야"))
				.andExpect(jsonPath("$[1].role").value("assistant"));
	}

	@Test
	void 없는_대화는_빈_목록이다() throws Exception {
		mockMvc.perform(get("/api/conversations/beta"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void 대화_목록에_저장된_ID_가_보인다() throws Exception {
		mockMvc.perform(get("/api/conversations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("alpha")));
	}

	@Test
	void 비우면_204_이고_내용이_사라진다() throws Exception {
		mockMvc.perform(delete("/api/conversations/alpha"))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/conversations/alpha"))
				.andExpect(jsonPath("$.length()").value(0));
	}
}
