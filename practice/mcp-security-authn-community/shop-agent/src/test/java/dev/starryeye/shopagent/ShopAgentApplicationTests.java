package dev.starryeye.shopagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM 실호출은 테스트하지 않는다. 배선만 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShopAgentApplicationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ClientRegistrationRepository clientRegistrationRepository;

	@Autowired
	OAuth2ClientProperties oAuth2ClientProperties;

	@Autowired
	ApplicationContext applicationContext;

	@Test
	void contextLoads() {
	}

	@Test
	void 로그인하지_않으면_로그인으로_보낸다() throws Exception {
		// 상태 코드(3xx)만 보면 formLogin 의 기본 /login 리다이렉트도 통과한다.
		// oauth2Login 이 실제로 authserver 등록으로 튕기는지까지 봐야 한다.
		// 패턴은 "/oauth2/authorization/**" 로 둔다 — AntPathMatcher 에서
		// "**/oauth2/authorization/authserver" 는 "/oauth2/authorization/authserver"
		// (선행 세그먼트가 없는 상대 경로) 와 매치하지 않는 것을 직접 확인했다.
		mockMvc.perform(get("/"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrlPattern("/oauth2/authorization/**"));
	}

	/**
	 * 등록이 정확히 하나여야 transport 커스터마이저가 토큰을 붙인다.
	 * 둘 이상이면 WARN 한 줄 남기고 조용히 아무 것도 하지 않는다.
	 */
	@Test
	void OAuth2_클라이언트_등록이_정확히_하나다() {
		var registration = clientRegistrationRepository.findByRegistrationId("authserver");

		assertThat(registration).isNotNull();
		assertThat(registration.getClientId()).isEqualTo("shop-agent");
		assertThat(registration.getAuthorizationGrantType().getValue())
				.isEqualTo("authorization_code");

		// transport 커스터마이저(HttpClientStreamableHttpTransportAutoConfiguration
		// .preRegisteredClientCustomizer)가 실제로 읽는 것은 이 맵이다. 0개나 2개 이상이면
		// WARN 한 줄만 남기고 no-op 커스터마이저가 설치되어 토큰이 조용히 안 붙는다.
		assertThat(oAuth2ClientProperties.getRegistration()).hasSize(1);
	}

	/**
	 * {@code spring.ai.mcp.client.type} 이 {@code SYNC} 가 아니면(기본값 포함,
	 * {@code matchIfMissing = true}) {@code HttpClientStreamableHttpTransportAutoConfiguration}
	 * 전체가 {@code @ConditionalOnProperty} 에 걸려 로드되지 않는다 — 바이트코드로
	 * 직접 확인했다. 그 자동설정이 만드는 {@code preRegisteredClientCustomizer} 빈이
	 * 없으면 토큰을 붙이는 경로 자체가 없다는 뜻이다. 이 빈은 있어도 등록 개수가
	 * 잘못되면 조용히 no-op 이 될 수 있으므로({@link #OAuth2_클라이언트_등록이_정확히_하나다})
	 * "존재"만으로 "정상 동작"을 증명하지는 않는다 — 이 테스트는 SYNC/ASYNC 스위치만 잡는다.
	 */
	@Test
	void SYNC_클라이언트_보안_자동설정이_로드된다() {
		assertThat(applicationContext.containsBean("preRegisteredClientCustomizer")).isTrue();
	}
}
