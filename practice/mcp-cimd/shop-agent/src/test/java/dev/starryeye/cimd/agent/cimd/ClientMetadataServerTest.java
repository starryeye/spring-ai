package dev.starryeye.cimd.agent.cimd;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import javax.net.ssl.SSLSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClientMetadataServerTest {

	static final String BASE_URL = "https://localhost:8172";

	static final String REDIRECT_URI = "http://localhost:8170/login/oauth2/code/authserver";

	ClientMetadataServer server;

	HttpClient client;

	@BeforeEach
	void start() throws Exception {
		ClientSigningKey key = ClientSigningKey.load(new ClassPathResource("test-certs/client-signing.p12"),
				"changeit", "client-signing");
		ClientMetadataDocuments documents = new ClientMetadataDocuments(
				new ClientMetadataProperties(BASE_URL, 0, null, null, null, null, REDIRECT_URI), key);
		this.server = new ClientMetadataServer(0, TestTls.server(), documents);
		this.server.start();
		this.client = HttpClient.newBuilder().sslContext(TestTls.client()).build();
	}

	@AfterEach
	void stop() {
		this.server.stop();
	}

	HttpResponse<String> get(String path) throws Exception {
		return this.client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + this.server.port() + path)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	@SuppressWarnings("unchecked")
	void ChatGPT형_문서는_private_key_jwt와_jwks_uri를_선언한다() throws Exception {
		HttpResponse<String> response = get("/oauth/client.json");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
		assertThat(response.headers().firstValue("Cache-Control")).hasValue("max-age=300");
		String body = response.body();
		assertThat((String) JsonPath.read(body, "$.client_id")).isEqualTo(BASE_URL + "/oauth/client.json");
		assertThat((String) JsonPath.read(body, "$.client_name")).isEqualTo("Shop Agent (ChatGPT형)");
		assertThat((List<String>) JsonPath.read(body, "$.redirect_uris")).containsExactly(REDIRECT_URI);
		assertThat((String) JsonPath.read(body, "$.token_endpoint_auth_method")).isEqualTo("private_key_jwt");
		assertThat((String) JsonPath.read(body, "$.token_endpoint_auth_signing_alg")).isEqualTo("RS256");
		assertThat((String) JsonPath.read(body, "$.jwks_uri")).isEqualTo(BASE_URL + "/oauth/jwks.json");
	}

	@Test
	void Claude형_문서는_none이고_key_정보가_없다() throws Exception {
		String body = get("/oauth/public-client.json").body();

		assertThat((String) JsonPath.read(body, "$.client_id")).isEqualTo(BASE_URL + "/oauth/public-client.json");
		assertThat((String) JsonPath.read(body, "$.client_name")).isEqualTo("Shop Agent (Claude형)");
		assertThat((String) JsonPath.read(body, "$.token_endpoint_auth_method")).isEqualTo("none");
		assertThat(JsonPath.<Map<String, Object>>read(body, "$")).doesNotContainKeys("jwks_uri", "token_endpoint_auth_signing_alg");
	}

	@Test
	void JWKS에는_public_key만_있다() throws Exception {
		String body = get("/oauth/jwks.json").body();

		Map<String, Object> key = JsonPath.read(body, "$.keys[0]");
		assertThat(key).containsKeys("kty", "n", "e", "kid").containsEntry("use", "sig").containsEntry("alg", "RS256");
		assertThat(key).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
	}

	@Test
	void 세_주소_밖은_404이고_GET이_아니면_405다() throws Exception {
		assertThat(get("/oauth/other.json").statusCode()).isEqualTo(404);
		assertThat(get("/").statusCode()).isEqualTo(404);

		HttpResponse<String> post = this.client.send(HttpRequest.newBuilder(
						URI.create("https://localhost:" + this.server.port() + "/oauth/client.json"))
				.POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(post.statusCode()).isEqualTo(405);
	}

	@Test
	void handshake만_하고_아무것도_보내지_않는_연결이_있어도_다른_요청은_바로_받는다() throws Exception {
		// browser의 preconnect가 이렇다. Authorization Server는 2초 안에 문서를 받지 못하면 client를 거절한다.
		try (SSLSocket idle = (SSLSocket) TestTls.client().getSocketFactory()
				.createSocket("localhost", this.server.port())) {
			idle.startHandshake();

			long started = System.nanoTime();
			HttpResponse<String> response = this.client.send(
					HttpRequest.newBuilder(URI.create("https://localhost:" + this.server.port() + "/oauth/client.json"))
							.timeout(Duration.ofSeconds(3)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
		}
	}

	@Test
	void 이_기기_안에서만_접속할_수_있다() {
		assertThat(this.server.isRunning()).isTrue();
		assertThat(this.server.address().getAddress().isLoopbackAddress()).isTrue();
	}
}
