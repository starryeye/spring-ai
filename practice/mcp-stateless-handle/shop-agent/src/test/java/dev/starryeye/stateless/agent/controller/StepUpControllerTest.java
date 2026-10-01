package dev.starryeye.stateless.agent.controller;

import dev.starryeye.stateless.agent.security.StepUpState;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StepUpControllerTest {

    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StepUpController()).build();

    @Test
    void 다시_요청하면_시도_기록을_지우고_step_up_authorization으로_보낸다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        StepUpState.of(session).start(List.of("products:write"));
        StepUpState.of(session).finish();

        this.mockMvc.perform(get("/step-up/retry").param("scope", "products:write").session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/authserver?step_up=products:write"));

        assertThat(StepUpState.of(session).attempted(List.of("products:write"))).isFalse();
    }
}
