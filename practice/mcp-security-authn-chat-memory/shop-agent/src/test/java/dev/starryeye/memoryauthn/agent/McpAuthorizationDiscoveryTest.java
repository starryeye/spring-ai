package dev.starryeye.memoryauthn.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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

    static final String RESOURCE = "http://localhost:8131/mcp";

    static final String ISSUER = "http://localhost:9020";

    static final String PROTECTED_RESOURCE_METADATA = """
            {"resource":"http://localhost:8131/mcp","authorization_servers":["http://localhost:9020"],\
            "bearer_methods_supported":["header"]}""";

    static final String AUTHORIZATION_SERVER_METADATA = """
            {"issuer":"http://localhost:9020","authorization_endpoint":"http://localhost:9020/oauth2/authorize",\
            "token_endpoint":"http://localhost:9020/oauth2/token","jwks_uri":"http://localhost:9020/oauth2/jwks",\
            "code_challenge_methods_supported":["S256"],"authorization_response_iss_parameter_supported":true}""";

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
        챌린지("Bearer resource_metadata=\"http://localhost:8131/.well-known/oauth-protected-resource/mcp\"");
        응답("http://localhost:8131/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
        응답("http://localhost:9020/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

        DiscoveredAuthorization discovered = this.discovery.discover(RESOURCE, ISSUER);

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
        응답("http://localhost:8131/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
        응답("http://localhost:9020/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

        assertThat(this.discovery.discover(RESOURCE, ISSUER).resource()).isEqualTo(RESOURCE);
        this.server.verify();
    }

    @Test
    void 경로형이_없으면_루트_well_known_으로_간다() {
        챌린지("Bearer");
        없음("http://localhost:8131/.well-known/oauth-protected-resource/mcp");
        응답("http://localhost:8131/.well-known/oauth-protected-resource", """
                {"resource":"http://localhost:8131","authorization_servers":["http://localhost:9020"]}""");
        응답("http://localhost:9020/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

        // 루트형이면 resource 는 서버 루트다. 클라이언트는 서버가 선언한 값을 그대로 쓴다.
        assertThat(this.discovery.discover(RESOURCE, ISSUER).resource()).isEqualTo("http://localhost:8131");
        this.server.verify();
    }

    @Test
    void 메타데이터의_resource_가_요청한_URL_과_다르면_실패한다() {
        챌린지("Bearer resource_metadata=\"http://localhost:8131/.well-known/oauth-protected-resource/mcp\"");
        응답("http://localhost:8131/.well-known/oauth-protected-resource/mcp", """
                {"resource":"http://evil.example/mcp","authorization_servers":["http://localhost:9020"]}""");

        assertThatExceptionOfType(McpDiscoveryException.class)
                .isThrownBy(() -> this.discovery.discover(RESOURCE, ISSUER))
                .withMessageContaining("resource");
    }

    @Test
    void RFC8414_가_없으면_OIDC_디스커버리로_간다() {
        챌린지("Bearer resource_metadata=\"http://localhost:8131/.well-known/oauth-protected-resource/mcp\"");
        응답("http://localhost:8131/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
        없음("http://localhost:9020/.well-known/oauth-authorization-server");
        응답("http://localhost:9020/.well-known/openid-configuration", AUTHORIZATION_SERVER_METADATA);

        assertThat(this.discovery.discover(RESOURCE, ISSUER).issuer()).isEqualTo(ISSUER);
        this.server.verify();
    }

    @Test
    void 메타데이터의_issuer_가_다르면_실패한다() {
        챌린지("Bearer resource_metadata=\"http://localhost:8131/.well-known/oauth-protected-resource/mcp\"");
        응답("http://localhost:8131/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
        응답("http://localhost:9020/.well-known/oauth-authorization-server", """
                {"issuer":"http://evil.example","authorization_endpoint":"http://evil.example/oauth2/authorize",\
                "token_endpoint":"http://evil.example/oauth2/token","code_challenge_methods_supported":["S256"]}""");

        assertThatExceptionOfType(McpDiscoveryException.class)
                .isThrownBy(() -> this.discovery.discover(RESOURCE, ISSUER))
                .withMessageContaining("issuer");
    }

    @Test
    void PKCE_S256_을_광고하지_않으면_진행하지_않는다() {
        챌린지("Bearer resource_metadata=\"http://localhost:8131/.well-known/oauth-protected-resource/mcp\"");
        응답("http://localhost:8131/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
        응답("http://localhost:9020/.well-known/oauth-authorization-server", """
                {"issuer":"http://localhost:9020","authorization_endpoint":"http://localhost:9020/oauth2/authorize",\
                "token_endpoint":"http://localhost:9020/oauth2/token"}""");

        assertThatExceptionOfType(McpDiscoveryException.class)
                .isThrownBy(() -> this.discovery.discover(RESOURCE, ISSUER))
                .withMessageContaining("S256");
    }

    @Test
    void PRM_의_Authorization_Server_가_자격증명의_issuer_가_아니면_metadata_를_요청하지_않는다() {
        // PRM 이 가리키는 주소로 곧장 GET 하면 공격자가 고른 주소로 요청이 나간다(SSRF).
        // 자격증명이 묶인 issuer 가 아니면 그 metadata 도 요청하지 않는다 — 기대하지 않은 요청이
        // 나가면 MockRestServiceServer 가 AssertionError 를 던져 이 테스트가 실패한다.
        챌린지("Bearer resource_metadata=\"http://localhost:8131/.well-known/oauth-protected-resource/mcp\"");
        응답("http://localhost:8131/.well-known/oauth-protected-resource/mcp", """
                {"resource":"http://localhost:8131/mcp","authorization_servers":["http://evil.example"]}""");

        assertThatExceptionOfType(McpDiscoveryException.class)
                .isThrownBy(() -> this.discovery.discover(RESOURCE, ISSUER))
                .withMessageContaining("http://evil.example");
        this.server.verify();
    }

    @Test
    void authorization_endpoint_가_http_URL_이_아니면_진행하지_않는다() {
        // MCP Security Best Practices — client 는 authorization URL 을 열기 전에 스킴을 확인한다(MUST).
        챌린지("Bearer resource_metadata=\"http://localhost:8131/.well-known/oauth-protected-resource/mcp\"");
        응답("http://localhost:8131/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
        응답("http://localhost:9020/.well-known/oauth-authorization-server",
                AUTHORIZATION_SERVER_METADATA.replace("http://localhost:9020/oauth2/authorize", "javascript:alert(1)"));

        assertThatExceptionOfType(McpDiscoveryException.class)
                .isThrownBy(() -> this.discovery.discover(RESOURCE, ISSUER))
                .withMessageContaining("authorization_endpoint");
    }

    @Test
    void token_endpoint_가_http_URL_이_아니면_진행하지_않는다() {
        챌린지("Bearer resource_metadata=\"http://localhost:8131/.well-known/oauth-protected-resource/mcp\"");
        응답("http://localhost:8131/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
        응답("http://localhost:9020/.well-known/oauth-authorization-server",
                AUTHORIZATION_SERVER_METADATA.replace("http://localhost:9020/oauth2/token", "file:///etc/passwd"));

        assertThatExceptionOfType(McpDiscoveryException.class)
                .isThrownBy(() -> this.discovery.discover(RESOURCE, ISSUER))
                .withMessageContaining("token_endpoint");
    }
}
