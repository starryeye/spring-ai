package dev.starryeye.shopagent;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/chat} 도 CSRF 를 검사한다. {@code csrf.spa()} 가 준 {@code XSRF-TOKEN} 쿠키 값을
 * {@code X-XSRF-TOKEN} 헤더로 되돌려 보내는 index.html 의 흐름을 그대로 밟는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatCsrfTest {

    /** Boot 4.1 의 {@code @AutoConfigureMockMvc} 는 springSecurity() 를 붙이지 않는다 — {@code @WithMockUser} 에 필요하다. */
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

    /**
     * 기본 {@code Answers.RETURNS_DEFAULTS} 는 배열 반환 타입을 특별 취급하지 않아
     * {@code getToolCallbacks()} 가 스텁 전(=context 기동 시점)에 호출되면 null 을
     * 준다 — {@code ToolCallingAutoConfiguration} 이 {@code List.of(pr.getToolCallbacks())}
     * 를 기동 중에 즉시 실행하다가 NPE 를 낸다. {@code RETURNS_MOCKS} 는 배열을 빈 배열로
     * 기본값을 준다.
     */
    @MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
    ToolCallbackProvider toolCallbackProvider;

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
                .willReturn(DiscoveryFixtures.discovered());
        given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[0]);
        // ChatClient 가 Prompt 를 만들며 chatModel.getOptions() 를 부른다 — 기본 답변은 null 이라 NPE 가 난다.
        given(this.chatModel.getOptions()).willReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        given(this.chatModel.stream(any(Prompt.class))).willReturn(
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("재고는 3개입니다"))))));
    }

    @Test
    @WithMockUser
    void CSRF_토큰_없이_채팅하면_403() throws Exception {
        this.mockMvc.perform(post("/api/chat").content("노트북 재고 있어?"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser
    void 페이지가_준_XSRF_TOKEN_을_헤더로_보내면_채팅이_시작된다() throws Exception {
        Cookie token = this.mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();

        this.mockMvc.perform(post("/api/chat").cookie(token).header("X-XSRF-TOKEN", token.getValue())
                        .content("노트북 재고 있어?"))
                .andExpect(request().asyncStarted());
    }
}
