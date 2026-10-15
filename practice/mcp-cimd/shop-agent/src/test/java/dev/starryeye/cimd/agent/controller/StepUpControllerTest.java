package dev.starryeye.cimd.agent.controller;

import dev.starryeye.cimd.agent.security.StepUpState;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StepUpControllerTest {

    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StepUpController()).build();

    @Test
    void 다시_요청하면_시도_기록을_지우고_step_up_authorization_주소를_돌려준다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        StepUpState.of(session).start(List.of("products:write"));
        StepUpState.of(session).finish();

        this.mockMvc.perform(post("/step-up/retry").param("scope", "products:write").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/oauth2/authorization/authserver?step_up=products:write"));

        assertThat(StepUpState.of(session).attempted(List.of("products:write"))).isFalse();
    }

    @Test
    void 상태를_바꾸는_요청이라_GET은_받지_않는다() throws Exception {
        // GET이면 다른 사이트의 <img src>로도 시도 기록을 지울 수 있다.
        MockHttpSession session = new MockHttpSession();
        StepUpState.of(session).start(List.of("products:write"));
        StepUpState.of(session).finish();

        this.mockMvc.perform(get("/step-up/retry").param("scope", "products:write").session(session))
                .andExpect(status().isMethodNotAllowed());

        assertThat(StepUpState.of(session).attempted(List.of("products:write"))).isTrue();
    }
}
