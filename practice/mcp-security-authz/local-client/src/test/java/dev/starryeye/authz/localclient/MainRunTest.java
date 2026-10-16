package dev.starryeye.authz.localclient;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainRunTest {

	@Test
	void 흐름이_도중에_실패해도_만든_HttpClient를_닫는다() throws Exception {
		try (FakeServer mcp = new FakeServer()) {
			// token 없는 요청에 401이 아니라 200이 오면 discovery가 멈춘다.
			mcp.on("POST", "/mcp", FakeServer.Reply.status(200));
			Main.Options options = new Main.Options(mcp.origin() + "/mcp", "http://127.0.0.1:1", false);
			AtomicReference<HttpClient> created = new AtomicReference<>();

			assertThatThrownBy(() -> Main.run(options, new PrintStream(OutputStream.nullOutputStream()), () -> {
				HttpClient http = Main.newHttpClient();
				created.set(http);
				return http;
			})).isInstanceOf(LocalClientException.class);

			assertThat(created.get().isTerminated()).isTrue();
		}
	}
}
