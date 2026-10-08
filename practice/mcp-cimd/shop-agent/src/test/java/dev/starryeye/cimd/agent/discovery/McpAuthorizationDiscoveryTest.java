package dev.starryeye.cimd.agent.discovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * MCP 2025-11-25 인가 §2.3 의 발견 순서를 검증한다.
 * 401 챌린지 → 보호 리소스 메타데이터 → 인가 서버 메타데이터.
 */
class McpAuthorizationDiscoveryTest {

	static final String RESOURCE = "http://localhost:8171/mcp";

	static final String ISSUER = "http://localhost:9060";

	static final ClientAuthenticationMethod METHOD = ClientAuthenticationMethod.PRIVATE_KEY_JWT;

	static final String PROTECTED_RESOURCE_METADATA = """
			{"resource":"http://localhost:8171/mcp","authorization_servers":["http://localhost:9060"],\
			"bearer_methods_supported":["header"]}""";

	static final String AUTHORIZATION_SERVER_METADATA = """
			{"issuer":"http://localhost:9060","authorization_endpoint":"http://localhost:9060/oauth2/authorize",\
			"token_endpoint":"http://localhost:9060/oauth2/token","jwks_uri":"http://localhost:9060/oauth2/jwks",\
			"code_challenge_methods_supported":["S256"],"authorization_response_iss_parameter_supported":true,\
			"client_id_metadata_document_supported":true,"token_endpoint_auth_methods_supported":["private_key_jwt","none"]}""";

	static final String PRM_WITH_SCOPES = """
			{"resource":"http://localhost:8171/mcp","authorization_servers":["http://localhost:9060"],\
			"scopes_supported":["products:read","products:write"]}""";

	MockRestServiceServer server;

	McpAuthorizationDiscovery discovery;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.discovery = new McpAuthorizationDiscovery(builder.build());
	}

	void 챌린지(String wwwAuthenticate) {
		this.server.expect(requestTo(RESOURCE)).andExpect(method(HttpMethod.POST))
				.andRespond(withUnauthorizedRequest().header("WWW-Authenticate", wwwAuthenticate));
	}

	void 응답(String url, String body) {
		this.server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	void 없음(String url) {
		this.server.expect(requestTo(url)).andExpect(method(HttpMethod.GET)).andRespond(withResourceNotFound());
	}

	@Test
	void 챌린지가_가리키는_메타데이터를_따라간다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		DiscoveredAuthorization discovered = this.discovery.discover(RESOURCE, METHOD);

		assertThat(discovered.resource()).isEqualTo(RESOURCE);
		assertThat(discovered.issuer()).isEqualTo(ISSUER);
		assertThat(discovered.authorizationEndpoint()).isEqualTo(ISSUER + "/oauth2/authorize");
		assertThat(discovered.tokenEndpoint()).isEqualTo(ISSUER + "/oauth2/token");
		assertThat(discovered.jwksUri()).isEqualTo(ISSUER + "/oauth2/jwks");
		assertThat(discovered.issParameterSupported()).isTrue();
		this.server.verify();
	}

	@Test
	void 챌린지에_위치가_없으면_경로형_well_known_을_먼저_본다() {
		챌린지("Bearer");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		assertThat(this.discovery.discover(RESOURCE, METHOD).resource()).isEqualTo(RESOURCE);
		this.server.verify();
	}

	@Test
	void 경로형이_없으면_루트_well_known_으로_간다() {
		챌린지("Bearer");
		없음("http://localhost:8171/.well-known/oauth-protected-resource/mcp");
		응답("http://localhost:8171/.well-known/oauth-protected-resource", """
				{"resource":"http://localhost:8171","authorization_servers":["http://localhost:9060"]}""");
		응답("http://localhost:9060/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		// 루트형이면 resource 는 서버 루트다. 클라이언트는 서버가 선언한 값을 그대로 쓴다.
		assertThat(this.discovery.discover(RESOURCE, METHOD).resource()).isEqualTo("http://localhost:8171");
		this.server.verify();
	}

	@Test
	void 메타데이터의_resource_가_요청한_URL_과_다르면_실패한다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", """
				{"resource":"http://evil.example/mcp","authorization_servers":["http://localhost:9060"]}""");

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE, METHOD))
				.withMessageContaining("resource");
	}

	@Test
	void RFC8414_가_없으면_OIDC_디스커버리로_간다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		없음("http://localhost:9060/.well-known/oauth-authorization-server");
		응답("http://localhost:9060/.well-known/openid-configuration", AUTHORIZATION_SERVER_METADATA);

		assertThat(this.discovery.discover(RESOURCE, METHOD).issuer()).isEqualTo(ISSUER);
		this.server.verify();
	}

	@Test
	void 메타데이터의_issuer_가_다르면_실패한다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server", """
				{"issuer":"http://evil.example","authorization_endpoint":"http://evil.example/oauth2/authorize",\
				"token_endpoint":"http://evil.example/oauth2/token","code_challenge_methods_supported":["S256"]}""");

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE, METHOD))
				.withMessageContaining("issuer");
	}

	@Test
	void PKCE_S256_을_광고하지_않으면_진행하지_않는다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server", """
				{"issuer":"http://localhost:9060","authorization_endpoint":"http://localhost:9060/oauth2/authorize",\
				"token_endpoint":"http://localhost:9060/oauth2/token"}""");

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE, METHOD))
				.withMessageContaining("S256");
	}

	@Test
	void authorization_endpoint_가_http_URL_이_아니면_진행하지_않는다() {
		// MCP Security Best Practices — client 는 authorization URL 을 열기 전에 스킴을 확인한다(MUST).
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server",
				AUTHORIZATION_SERVER_METADATA.replace("http://localhost:9060/oauth2/authorize", "javascript:alert(1)"));

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE, METHOD))
				.withMessageContaining("authorization_endpoint");
	}

	@Test
	void token_endpoint_가_http_URL_이_아니면_진행하지_않는다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server",
				AUTHORIZATION_SERVER_METADATA.replace("http://localhost:9060/oauth2/token", "file:///etc/passwd"));

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE, METHOD))
				.withMessageContaining("token_endpoint");
	}

	@Test
	void authorization_endpoint_가_loopback_이_아닌_http_URL_이면_진행하지_않는다() {
		// MCP Security Best Practices — http 는 loopback 주소에만 허용된다(MUST). 스킴만 보는 검사로는 막을 수 없는 URL 이다.
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server",
				AUTHORIZATION_SERVER_METADATA.replace("http://localhost:9060/oauth2/authorize", "http://evil.example/oauth2/authorize"));

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE, METHOD))
				.withMessageContaining("authorization_endpoint");
	}

	@Test
	void https_authorization_endpoint_는_host_와_상관없이_받는다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server",
				AUTHORIZATION_SERVER_METADATA.replace("http://localhost:9060/oauth2/authorize", "https://auth.example/oauth2/authorize"));

		assertThat(this.discovery.discover(RESOURCE, METHOD).authorizationEndpoint())
				.isEqualTo("https://auth.example/oauth2/authorize");
		this.server.verify();
	}

	@Test
	void 챌린지의_scope를_먼저_고른다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\", "
				+ "scope=\"products:read\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PRM_WITH_SCOPES);
		응답("http://localhost:9060/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		assertThat(this.discovery.discover(RESOURCE, METHOD).scopes()).containsExactly("products:read");
	}

	@Test
	void 챌린지에_scope가_없으면_scopes_supported를_모두_고른다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PRM_WITH_SCOPES);
		응답("http://localhost:9060/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		assertThat(this.discovery.discover(RESOURCE, METHOD).scopes())
				.containsExactly("products:read", "products:write");
	}

	@Test
	void 둘_다_없으면_scope를_고르지_않는다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		assertThat(this.discovery.discover(RESOURCE, METHOD).scopes()).isEmpty();
	}

	@Test
	void 챌린지의_여러_scope는_공백으로_나눈다() {
		assertThat(McpAuthorizationDiscovery.selectScopes("products:read  products:write", null))
				.containsExactly("products:read", "products:write");
	}

	@Test
	void CIMD를_알리지_않는_서버면_멈춘다() {
		// 문서 주소를 client_id로 받지 않는 서버다. 그대로 가면 모르는 client로 거절당한다.
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server",
				AUTHORIZATION_SERVER_METADATA.replace("\"client_id_metadata_document_supported\":true,", ""));

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE, METHOD))
				.withMessageContaining("client_id_metadata_document_supported");
	}

	@Test
	void 고른_인증_방식을_받지_않는_서버면_멈춘다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server",
				AUTHORIZATION_SERVER_METADATA.replace("[\"private_key_jwt\",\"none\"]", "[\"none\"]"));

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE, METHOD))
				.withMessageContaining("private_key_jwt");
	}

	@Test
	void Claude형은_none을_받는_서버면_진행한다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8171/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9060/.well-known/oauth-authorization-server",
				AUTHORIZATION_SERVER_METADATA.replace("[\"private_key_jwt\",\"none\"]", "[\"none\"]"));

		assertThat(this.discovery.discover(RESOURCE, ClientAuthenticationMethod.NONE).issuer()).isEqualTo(ISSUER);
		this.server.verify();
	}
}
