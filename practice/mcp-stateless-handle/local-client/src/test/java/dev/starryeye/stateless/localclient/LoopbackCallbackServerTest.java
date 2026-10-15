package dev.starryeye.stateless.localclient;

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
		try (LoopbackCallbackServer server = LoopbackCallbackServer.start("xyz")) {
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
		try (LoopbackCallbackServer server = LoopbackCallbackServer.start("xyz")) {
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
		try (LoopbackCallbackServer server = LoopbackCallbackServer.start("xyz")) {
			assertThat(get(URI.create(server.redirectUri() + "/x"))).isEqualTo(404);

			get(URI.create(server.redirectUri() + "?code=abc&state=xyz"));

			assertThat(server.await(Duration.ofSeconds(5))).containsEntry("code", "abc");
		}
	}

	@Test
	void state가_다르거나_없는_callback은_400이고_정상_callback을_계속_기다린다() throws Exception {
		// 같은 기기의 다른 process나 browser의 악성 페이지가 loopback 포트를 훑어 먼저 보낸 요청이
		// login 결과로 확정되면, 정상 login이 "state가 다르다"로 끝난다. 그런 요청은 거절하고 계속 기다린다.
		try (LoopbackCallbackServer server = LoopbackCallbackServer.start("xyz")) {
			assertThat(get(URI.create(server.redirectUri() + "?code=evil&state=other"))).isEqualTo(400);
			assertThat(get(URI.create(server.redirectUri() + "?code=evil"))).isEqualTo(400);
			assertThat(get(URI.create(server.redirectUri() + "?x=1"))).isEqualTo(400);

			assertThat(get(URI.create(server.redirectUri() + "?code=abc&state=xyz"))).isEqualTo(200);

			assertThat(server.await(Duration.ofSeconds(5))).containsEntry("code", "abc");
		}
	}

	@Test
	void GET이_아닌_callback은_state가_맞아도_400이고_결과에_영향이_없다() throws Exception {
		try (LoopbackCallbackServer server = LoopbackCallbackServer.start("xyz")) {
			URI callback = URI.create(server.redirectUri() + "?code=evil&state=xyz");
			int status = this.http.send(HttpRequest.newBuilder(callback).POST(HttpRequest.BodyPublishers.noBody())
					.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
			assertThat(status).isEqualTo(400);

			get(URI.create(server.redirectUri() + "?code=abc&state=xyz"));

			assertThat(server.await(Duration.ofSeconds(5))).containsEntry("code", "abc");
		}
	}

	@Test
	void Authorization_Server의_오류_응답도_state가_맞으면_결과로_받는다() throws Exception {
		// 오류 응답에도 state가 실린다(RFC 6749 4.1.2.1). 오류 판단은 AuthorizationResponse가 한다.
		try (LoopbackCallbackServer server = LoopbackCallbackServer.start("xyz")) {
			assertThat(get(URI.create(server.redirectUri() + "?error=access_denied&state=xyz"))).isEqualTo(200);

			assertThat(server.await(Duration.ofSeconds(5))).containsEntry("error", "access_denied");
		}
	}

	@Test
	void await가_시간_안에_callback을_못_받으면_login_시간_초과를_알린다() throws Exception {
		try (LoopbackCallbackServer server = LoopbackCallbackServer.start("xyz")) {
			assertThatThrownBy(() -> server.await(Duration.ofMillis(200)))
					.isInstanceOf(LocalClientException.class).hasMessageContaining("login");
		}
	}
}
