package dev.starryeye.localclient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryTest {

	FakeServer mcp;

	FakeServer as;

	Discovery discovery = new Discovery(HttpClient.newHttpClient());

	@BeforeEach
	void setUp() throws Exception {
		this.mcp = new FakeServer();
		this.as = new FakeServer();
	}

	@AfterEach
	void tearDown() {
		this.mcp.close();
		this.as.close();
	}

	String resource() {
		return this.mcp.origin() + "/mcp";
	}

	void challenge() {
		this.mcp.on("POST", "/mcp", new FakeServer.Reply(401, Map.of("WWW-Authenticate",
				"Bearer resource_metadata=\"" + this.mcp.origin() + "/.well-known/oauth-protected-resource/mcp\""), ""));
	}

	void prm(String resource, String issuer) {
		this.mcp.on("GET", "/.well-known/oauth-protected-resource/mcp", FakeServer.Reply.json(
				"{\"resource\":\"%s\",\"authorization_servers\":[\"%s\"]}".formatted(resource, issuer)));
	}

	void metadata(String issuer, String authorizationEndpoint, String methods) {
		this.as.on("GET", "/.well-known/oauth-authorization-server", FakeServer.Reply.json("""
				{"issuer":"%s","authorization_endpoint":"%s","token_endpoint":"%s/oauth2/token",\
				"code_challenge_methods_supported":%s,"authorization_response_iss_parameter_supported":true}"""
				.formatted(issuer, authorizationEndpoint, this.as.origin(), methods)));
	}

	@Test
	void 필요한_세_요청으로_Authorization_Server를_찾는다() {
		challenge();
		prm(resource(), this.as.origin());
		metadata(this.as.origin(), this.as.origin() + "/oauth2/authorize", "[\"S256\"]");

		AuthorizationServer server = this.discovery.discover(resource(), this.as.origin());

		assertThat(server.resource()).isEqualTo(resource());
		assertThat(server.issuer()).isEqualTo(this.as.origin());
		assertThat(server.authorizationEndpoint()).isEqualTo(this.as.origin() + "/oauth2/authorize");
		assertThat(server.tokenEndpoint()).isEqualTo(this.as.origin() + "/oauth2/token");
		assertThat(server.issParameterSupported()).isTrue();
	}

	@Test
	void header가_없으면_path가_붙은_well_known_주소로_찾는다() {
		this.mcp.on("POST", "/mcp", FakeServer.Reply.status(401));
		prm(resource(), this.as.origin());
		metadata(this.as.origin(), this.as.origin() + "/oauth2/authorize", "[\"S256\"]");

		assertThat(this.discovery.discover(resource(), this.as.origin()).issuer()).isEqualTo(this.as.origin());
	}

	@Test
	void PRM의_resource가_부른_주소와_다르면_멈춘다() {
		challenge();
		prm(this.mcp.origin() + "/other", this.as.origin());

		assertThatThrownBy(() -> this.discovery.discover(resource(), this.as.origin()))
				.isInstanceOf(LocalClientException.class).hasMessageContaining("resource");
	}

	@Test
	void PRM이_모르는_Authorization_Server를_가리키면_metadata를_요청하지_않는다() {
		challenge();
		prm(resource(), "http://127.0.0.1:1");

		assertThatThrownBy(() -> this.discovery.discover(resource(), this.as.origin()))
				.isInstanceOf(LocalClientException.class).hasMessageContaining("http://127.0.0.1:1");
		assertThat(this.as.requests).isEmpty();
	}

	@Test
	void metadata의_issuer가_다르면_멈춘다() {
		challenge();
		prm(resource(), this.as.origin());
		metadata("http://evil.example", this.as.origin() + "/oauth2/authorize", "[\"S256\"]");

		assertThatThrownBy(() -> this.discovery.discover(resource(), this.as.origin()))
				.isInstanceOf(LocalClientException.class).hasMessageContaining("issuer");
	}

	@Test
	void S256을_지원하지_않으면_멈춘다() {
		challenge();
		prm(resource(), this.as.origin());
		metadata(this.as.origin(), this.as.origin() + "/oauth2/authorize", "[\"plain\"]");

		assertThatThrownBy(() -> this.discovery.discover(resource(), this.as.origin()))
				.isInstanceOf(LocalClientException.class).hasMessageContaining("S256");
	}

	@Test
	void loopback이_아닌_http_authorization_endpoint는_받지_않는다() {
		challenge();
		prm(resource(), this.as.origin());
		metadata(this.as.origin(), "http://evil.example/authorize", "[\"S256\"]");

		assertThatThrownBy(() -> this.discovery.discover(resource(), this.as.origin()))
				.isInstanceOf(LocalClientException.class).hasMessageContaining("authorization_endpoint");
	}

	@Test
	void token_없는_요청에_401이_아니면_멈춘다() {
		this.mcp.on("POST", "/mcp", FakeServer.Reply.status(200));

		assertThatThrownBy(() -> this.discovery.discover(resource(), this.as.origin()))
				.isInstanceOf(LocalClientException.class).hasMessageContaining("401");
	}
}
