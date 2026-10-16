package dev.starryeye.community.agent;

import dev.starryeye.community.agent.config.McpSecurityConfig;
import dev.starryeye.community.agent.discovery.DiscoveryFixtures;
import dev.starryeye.community.agent.discovery.McpAuthorizationDiscovery;

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
 * LLM을 실제로 부르는 것은 테스트하지 않는다. 연결만 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShopAgentApplicationTests {

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	McpAuthorizationDiscovery discovery;

	@org.junit.jupiter.api.BeforeEach
	void 발견_결과를_고정한다() {
		org.mockito.BDDMockito.given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
	}

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
		// 상태 코드(3xx)만 보면 formLogin의 기본 /login redirect도 통과한다.
		// oauth2Login이 실제로 authserver 등록으로 보내는지까지 봐야 한다.
		// 패턴은 "/oauth2/authorization/**"로 둔다. AntPathMatcher에서
		// "**/oauth2/authorization/authserver"는 "/oauth2/authorization/authserver"
		// (앞 segment가 없는 상대 경로)와 매치하지 않는다.
		mockMvc.perform(get("/"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrlPattern("/oauth2/authorization/**"));
	}

	/**
	 * client 등록이 정확히 하나여야 transport customizer가 token을 붙인다.
	 * 둘 이상이면 WARN 한 줄만 남기고 조용히 아무것도 하지 않는다.
	 */
	@Test
	void OAuth2_클라이언트_등록이_정확히_하나다() {
		var registration = clientRegistrationRepository.findByRegistrationId(McpSecurityConfig.REGISTRATION_ID);

		assertThat(registration).isNotNull();
		assertThat(registration.getClientId()).isEqualTo("shop-agent");
		assertThat(registration.getAuthorizationGrantType().getValue()).isEqualTo("authorization_code");
		// endpoint는 설정이 아니라 discovery 결과에서 온다.
		assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(DiscoveryFixtures.ISSUER);
		assertThat(registration.getProviderDetails().getAuthorizationUri())
				.isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/authorize");
		// module의 transport customizer가 실제로 읽는 것은 이 map이다.
		assertThat(oAuth2ClientProperties.getRegistration()).hasSize(1);
	}

	/**
	 * {@code spring.ai.mcp.client.type}이 {@code SYNC}가 아니면(기본값 포함,
	 * {@code matchIfMissing = true}) {@code HttpClientStreamableHttpTransportAutoConfiguration}
	 * 전체가 {@code @ConditionalOnProperty}에 걸려 로드되지 않는다(bytecode로 확인).
	 * 그 자동 구성이 만드는 {@code preRegisteredClientCustomizer} bean이 없으면 token을 붙이는 경로 자체가 없다.
	 * 이 bean이 있어도 등록 개수가 잘못되면 조용히 아무것도 하지 않을 수 있다({@link #OAuth2_클라이언트_등록이_정확히_하나다}).
	 * 그래서 bean이 "있다"는 것만으로 "정상 동작"을 증명하지는 않는다. 이 테스트는 SYNC/ASYNC 스위치만 잡는다.
	 */
	@Test
	void SYNC_클라이언트_보안_자동설정이_로드된다() {
		assertThat(applicationContext.containsBean("preRegisteredClientCustomizer")).isTrue();
	}

	/**
	 * browser로 보내는 authorization request에 PKCE challenge와 resource가 있어야 한다.
	 * 둘 중 하나라도 빠지면 Authorization Server가 거부하거나(PKCE), token의 aud가 좁혀지지 않는다(resource).
	 */
	@Test
	void 인가_요청에_PKCE_와_resource_가_실린다() throws Exception {
		String location = mockMvc.perform(get("/oauth2/authorization/" + McpSecurityConfig.REGISTRATION_ID))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getRedirectedUrl();

		var parameters = org.springframework.web.util.UriComponentsBuilder.fromUriString(location).build()
				.getQueryParams();

		assertThat(parameters.getFirst("code_challenge_method")).isEqualTo("S256");
		assertThat(parameters.getFirst("code_challenge")).isNotBlank();
		assertThat(org.springframework.web.util.UriUtils.decode(parameters.getFirst("resource"),
				java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(DiscoveryFixtures.RESOURCE);
	}
}
