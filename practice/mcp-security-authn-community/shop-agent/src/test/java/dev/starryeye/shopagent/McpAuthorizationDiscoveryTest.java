package dev.starryeye.shopagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.common.url.DefaultUrlValidator;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class McpAuthorizationDiscoveryTest {

    static final String RESOURCE = "http://localhost:8101/mcp";

    static final String ISSUER = "http://localhost:9000";

    static final String PROTECTED_RESOURCE_METADATA = """
            {"resource":"http://localhost:8101/mcp","authorization_servers":["http://localhost:9000"],\
            "bearer_methods_supported":["header"]}""";

    static final String AUTHORIZATION_SERVER_METADATA = """
            {"issuer":"http://localhost:9000","authorization_endpoint":"http://localhost:9000/oauth2/authorize",\
            "token_endpoint":"http://localhost:9000/oauth2/token","jwks_uri":"http://localhost:9000/oauth2/jwks",\
            "code_challenge_methods_supported":["S256"],"authorization_response_iss_parameter_supported":true}""";

    MockRestServiceServer server;

    McpAuthorizationDiscovery discovery;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        // 학습 환경은 localhost HTTP 다. 기본값은 HTTPS 만 허용한다.
        this.discovery = new McpAuthorizationDiscovery(
                new McpMetadataDiscoveryService(restClient, new DefaultUrlValidator(true)), restClient);
    }

    void 챌린지() {
        this.server.expect(requestTo(RESOURCE)).andExpect(method(HttpMethod.POST))
                .andRespond(withUnauthorizedRequest().header("WWW-Authenticate",
                        "Bearer resource_metadata=\"http://localhost:8101/.well-known/oauth-protected-resource/mcp\""));
    }

    void 응답(String url, String body) {
        this.server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void 챌린지에서_인가_서버까지_찾아낸다() {
        챌린지();
        응답("http://localhost:8101/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
        응답("http://localhost:9000/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

        DiscoveredAuthorization discovered = this.discovery.discover(RESOURCE);

        assertThat(discovered.resource()).isEqualTo(RESOURCE);
        assertThat(discovered.issuer()).isEqualTo(ISSUER);
        assertThat(discovered.tokenEndpoint()).isEqualTo(ISSUER + "/oauth2/token");
        assertThat(discovered.issParameterSupported()).isTrue();
        this.server.verify();
    }

    @Test
    void 메타데이터의_issuer_가_다르면_실패한다() {
        챌린지();
        응답("http://localhost:8101/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
        응답("http://localhost:9000/.well-known/oauth-authorization-server", """
                {"issuer":"http://evil.example","authorization_endpoint":"http://evil.example/oauth2/authorize",\
                "token_endpoint":"http://evil.example/oauth2/token","code_challenge_methods_supported":["S256"]}""");

        assertThatExceptionOfType(McpDiscoveryException.class)
                .isThrownBy(() -> this.discovery.discover(RESOURCE))
                .withMessageContaining("issuer");
    }

    @Test
    void PKCE_S256_을_광고하지_않으면_진행하지_않는다() {
        챌린지();
        응답("http://localhost:8101/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
        응답("http://localhost:9000/.well-known/oauth-authorization-server", """
                {"issuer":"http://localhost:9000","authorization_endpoint":"http://localhost:9000/oauth2/authorize",\
                "token_endpoint":"http://localhost:9000/oauth2/token"}""");

        assertThatExceptionOfType(McpDiscoveryException.class)
                .isThrownBy(() -> this.discovery.discover(RESOURCE))
                .withMessageContaining("S256");
    }
}
