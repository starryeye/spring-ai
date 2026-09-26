package dev.starryeye.localclient;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoopbackCallbackServerTest {

	HttpClient http = HttpClient.newHttpClient();

	int get(URI uri) throws Exception {
		return this.http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding())
				.statusCode();
	}

	@Test
	void _127_0_0_1의_임시_포트에서_callback을_받는다() throws Exception {
		try (LoopbackCallbackServer server = LoopbackCallbackServer.start()) {
			URI redirect = server.redirectUri();
			assertThat(redirect.getHost()).isEqualTo("127.0.0.1");
			assertThat(redirect.getPath()).isEqualTo("/callback");
			assertThat(redirect.getPort()).isPositive();

			assertThat(get(URI.create(redirect + "?code=abc&state=xyz"))).isEqualTo(200);

			Map<String, String> params = server.await(Duration.ofSeconds(5));
			assertThat(params).containsEntry("code", "abc").containsEntry("state", "xyz");
		}
	}

	@Test
	void callback이_아닌_경로는_404이고_결과에_영향이_없다() throws Exception {
		try (LoopbackCallbackServer server = LoopbackCallbackServer.start()) {
			URI favicon = server.redirectUri().resolve("/favicon.ico");
			assertThat(get(favicon)).isEqualTo(404);

			get(URI.create(server.redirectUri() + "?code=abc&state=xyz"));
			get(URI.create(server.redirectUri() + "?code=second&state=xyz"));

			assertThat(server.await(Duration.ofSeconds(5))).containsEntry("code", "abc");
		}
	}

	@Test
	void callback_아래의_하위_경로는_handler_안에서_직접_404가_된다() throws Exception {
		// `/favicon.ico`는 HttpServer에 등록된 context가 아예 없어 handler를 타지 않고도 404가 난다.
		// `/callback/x`는 `/callback` context와 경로가 겹쳐 handler까지 들어온 뒤, PATH가 정확히 같은지
		// 보는 handler 자신의 guard에서 404가 나야 한다. 이 guard가 없으면 첫 callback으로 잘못 받아들인다.
		try (LoopbackCallbackServer server = LoopbackCallbackServer.start()) {
			assertThat(get(URI.create(server.redirectUri() + "/x"))).isEqualTo(404);

			get(URI.create(server.redirectUri() + "?code=abc&state=xyz"));

			assertThat(server.await(Duration.ofSeconds(5))).containsEntry("code", "abc");
		}
	}

	@Test
	void await가_시간_안에_callback을_못_받으면_login_시간_초과를_알린다() throws Exception {
		try (LoopbackCallbackServer server = LoopbackCallbackServer.start()) {
			assertThatThrownBy(() -> server.await(Duration.ofMillis(200)))
					.isInstanceOf(LocalClientException.class).hasMessageContaining("login");
		}
	}
}
