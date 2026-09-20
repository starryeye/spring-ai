package dev.starryeye.authserver;

import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code McpAuthorizationStandardConfig} 의 javadoc 이 서술하는 덮어쓰기를 실제로 고정한다.
 * 이 프로젝트 자체는 CIMD 를 켜지 않지만(properties 로 켜는 경로도 없다), {@code cimd(true)}
 * 를 호출하는 {@code Customizer<McpAuthorizationServerConfigurer>} 빈이 하나라도 있으면
 * 재현된다는 것을 이 테스트 전용 빈으로 보인다.
 *
 * <p>이 테스트는 "이래야 한다"가 아니라 "지금 이렇다"를 고정한다 — 모듈이 심으려던
 * {@code client_id_metadata_document_supported} claim 은 사라지고, 이 클래스가 심는
 * claim 만 남는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CimdMetadataOverwriteTest {

    @TestConfiguration
    static class CimdActivatingConfig {

        @Bean
        Customizer<McpAuthorizationServerConfigurer> cimd를_켜는_테스트용_커스터마이저() {
            return configurer -> configurer.cimd(true);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void CIMD_를_켜도_모듈이_심으려던_claim_은_사라지고_이_클래스의_claim_만_남는다() throws Exception {
        this.mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                // 모듈의 init() 이 cimd(true) 로 먼저 심는 claim 이지만, McpAuthorizationStandardConfig
                // 의 authorizationServerMetadataCustomizer 호출이 같은 필드를 나중에 덮어써 사라진다.
                .andExpect(jsonPath("$.client_id_metadata_document_supported").doesNotExist())
                // 반대로 이 클래스가 심는 claim 은 남아 있다 — 마지막에 실행되는 쪽이 이겼을 뿐이다.
                .andExpect(jsonPath("$.authorization_response_iss_parameter_supported").value(true));
    }
}
