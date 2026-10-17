package dev.starryeye.modern.authserver.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IssuerIdentifyingAuthorizationResponseHandlerTest {

	static final String ISSUER = "http://localhost:9070";

	static final String CLIENT_ID = "https://attacker.example/c.json";

	static final String REDIRECT_URI = "https://attacker.example/cb";

	static final Authentication USER = UsernamePasswordAuthenticationToken.authenticated("user", null,
			AuthorityUtils.createAuthorityList("ROLE_USER"));

	/** login 전 요청에서 Spring이 principal 자리에 넣는 값과 같은 꼴이다. */
	static final Authentication ANONYMOUS = new AnonymousAuthenticationToken("anonymous", "anonymousUser",
			AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

	IssuerIdentifyingAuthorizationResponseHandler handler = new IssuerIdentifyingAuthorizationResponseHandler();

	@BeforeEach
	void context() {
		AuthorizationServerSettings settings = AuthorizationServerSettings.builder().issuer(ISSUER).build();
		AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
			@Override
			public String getIssuer() {
				return ISSUER;
			}

			@Override
			public AuthorizationServerSettings getAuthorizationServerSettings() {
				return settings;
			}
		});
	}

	@AfterEach
	void resetContext() {
		AuthorizationServerContextHolder.resetContext();
	}

	static OAuth2AuthorizationCodeRequestAuthenticationException 오류(String errorCode, Authentication principal) {
		OAuth2AuthorizationCodeRequestAuthenticationToken request = new OAuth2AuthorizationCodeRequestAuthenticationToken(
				ISSUER + "/oauth2/authorize", CLIENT_ID, principal, REDIRECT_URI, "state-1", Set.of("openid"), Map.of());
		return new OAuth2AuthorizationCodeRequestAuthenticationException(
				new OAuth2Error(errorCode, "설명 <b>그대로</b>", "https://example.com/error"), request);
	}

	MockHttpServletResponse 실패(OAuth2AuthorizationCodeRequestAuthenticationException exception) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.handler.onAuthenticationFailure(new MockHttpServletRequest("GET", "/oauth2/authorize"), response, exception);
		return response;
	}

	@Test
	void 로그인한_사용자가_거절하면_redirect_uri로_access_denied와_iss를_보낸다() throws Exception {
		MockHttpServletResponse response = 실패(오류(OAuth2ErrorCodes.ACCESS_DENIED, USER));

		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getRedirectedUrl()).startsWith(REDIRECT_URI + "?error=access_denied")
				.contains("state=state-1")
				.contains("iss=" + "http%3A%2F%2Flocalhost%3A9070");
	}

	@Test
	void 로그인하지_않았으면_access_denied라도_redirect하지_않는다() throws Exception {
		MockHttpServletResponse response = 실패(오류(OAuth2ErrorCodes.ACCESS_DENIED, ANONYMOUS));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getRedirectedUrl()).isNull();
	}

	@Test
	void 로그인했어도_거절이_아닌_오류는_redirect하지_않고_오류_코드와_설명만_text로_보인다() throws Exception {
		MockHttpServletResponse response = 실패(오류(OAuth2ErrorCodes.INVALID_SCOPE, USER));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getHeader("Location")).isNull();
		assertThat(response.getContentType()).isEqualTo("text/plain;charset=UTF-8");
		assertThat(response.getContentAsString(StandardCharsets.UTF_8))
				.contains("invalid_scope")
				.contains("설명 <b>그대로</b>")
				.doesNotContain(REDIRECT_URI)
				.doesNotContain("https://example.com/error");
	}

	@Test
	void 로그인_전_오류도_text로_보인다() throws Exception {
		MockHttpServletResponse response = 실패(오류(OAuth2ErrorCodes.INVALID_REQUEST, ANONYMOUS));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentType()).isEqualTo("text/plain;charset=UTF-8");
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("invalid_request");
	}
}
