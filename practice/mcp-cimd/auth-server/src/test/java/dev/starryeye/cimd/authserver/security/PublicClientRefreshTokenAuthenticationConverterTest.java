package dev.starryeye.cimd.authserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import static org.assertj.core.api.Assertions.assertThat;

class PublicClientRefreshTokenAuthenticationConverterTest {

	static final String CLAUDE = "https://localhost:8172/oauth/public-client.json";

	PublicClientRefreshTokenAuthenticationConverter converter = new PublicClientRefreshTokenAuthenticationConverter();

	static MockHttpServletRequest refresh() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
		request.addParameter("grant_type", "refresh_token");
		request.addParameter("refresh_token", "r1");
		request.addParameter("client_id", CLAUDE);
		return request;
	}

	@Test
	void client_id만_있는_refresh_요청을_public_client_인증으로_바꾼다() {
		OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) this.converter.convert(refresh());

		assertThat(token.getPrincipal()).isEqualTo(CLAUDE);
		assertThat(token.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
		assertThat(token.getAdditionalParameters()).containsEntry("grant_type", "refresh_token");
	}

	@Test
	void 다른_client_인증이_있으면_맡지_않는다() {
		MockHttpServletRequest basic = refresh();
		basic.addHeader("Authorization", "Basic eDp5");
		assertThat(this.converter.convert(basic)).isNull();

		MockHttpServletRequest assertion = refresh();
		assertion.addParameter("client_assertion", "a.b.c");
		assertThat(this.converter.convert(assertion)).isNull();

		MockHttpServletRequest secret = refresh();
		secret.addParameter("client_secret", "s");
		assertThat(this.converter.convert(secret)).isNull();
	}

	@Test
	void refresh_요청이_아니거나_client_id가_하나가_아니면_맡지_않는다() {
		MockHttpServletRequest code = refresh();
		code.setParameter("grant_type", "authorization_code");
		assertThat(this.converter.convert(code)).isNull();

		MockHttpServletRequest twice = refresh();
		twice.addParameter("client_id", "https://other.example/client.json");
		assertThat(this.converter.convert(twice)).isNull();

		MockHttpServletRequest none = refresh();
		none.removeParameter("client_id");
		assertThat(this.converter.convert(none)).isNull();
	}

	@Test
	void token_endpoint가_아닌_endpoint는_맡지_않는다() {
		// 이 endpoint들도 Spring의 client 인증 filter를 거친다.
		// 맡으면 인증 없이 client_id만으로 public client가 되어, 예를 들어 introspection으로 아무 token이나 읽는다.
		for (String path : new String[] { "/oauth2/introspect", "/oauth2/revoke", "/oauth2/par",
				"/oauth2/device_authorization" }) {
			MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
			request.addParameter("grant_type", "refresh_token");
			request.addParameter("token", "r1");
			request.addParameter("client_id", CLAUDE);

			assertThat(this.converter.convert(request)).as(path).isNull();
		}
	}

	@Test
	void token_endpoint라도_POST가_아니면_맡지_않는다() {
		MockHttpServletRequest get = new MockHttpServletRequest("GET", "/oauth2/token");
		get.addParameter("grant_type", "refresh_token");
		get.addParameter("refresh_token", "r1");
		get.addParameter("client_id", CLAUDE);

		assertThat(this.converter.convert(get)).isNull();
	}

	@Test
	void 설정한_token_endpoint_경로를_따른다() {
		AuthorizationServerSettings settings = AuthorizationServerSettings.builder().tokenEndpoint("/custom/token").build();
		AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
			@Override
			public String getIssuer() {
				return "http://localhost:9060";
			}

			@Override
			public AuthorizationServerSettings getAuthorizationServerSettings() {
				return settings;
			}
		});
		try {
			MockHttpServletRequest custom = refresh();
			custom.setRequestURI("/custom/token");
			assertThat(this.converter.convert(custom)).isNotNull();

			assertThat(this.converter.convert(refresh())).isNull();
		}
		finally {
			AuthorizationServerContextHolder.resetContext();
		}
	}
}
