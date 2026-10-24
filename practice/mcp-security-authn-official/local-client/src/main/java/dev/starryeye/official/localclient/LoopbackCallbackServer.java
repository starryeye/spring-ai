package dev.starryeye.official.localclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * browser가 login을 마치고 돌아올 callback 주소를 이 기기 안에 연다.
 *
 * <p>`127.0.0.1`의 비어 있는 포트를 운영체제에서 받는다.
 * Authorization Server는 loopback redirect URI의 포트를 비교하지 않으므로,
 * 등록된 `http://127.0.0.1:8123/callback`과 포트가 달라도 된다(RFC 8252 7.3절).
 *
 * <p>`GET /callback`이고 `state`가 이번 authorization request의 값과 같은 요청만 결과로 쓴다.
 * 같은 기기의 다른 process나 browser의 다른 페이지도 이 포트에 요청을 보낼 수 있으므로, 처음 온 요청을 결과로 확정하면 그런 요청 하나로 정상 login을 끝내 버릴 수 있다.
 * 그래서 method가 다르거나 `state`가 없거나 다른 요청은 `400`으로 답하고 계속 기다린다.
 * `/callback`이 아닌 경로(`/favicon.ico` 등)는 404다.
 */
public final class LoopbackCallbackServer implements AutoCloseable {

	private static final String PATH = "/callback";

	private final HttpServer server;

	private final String expectedState;

	private final CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();

	private LoopbackCallbackServer(HttpServer server, String expectedState) {
		this.server = server;
		this.expectedState = expectedState;
		this.server.createContext(PATH, this::handle);
		this.server.start();
	}

	/**
	 * @param expectedState authorization request에 넣어 보낸 `state`.
	 * 이 값이 같은 callback만 결과로 쓴다.
	 */
	public static LoopbackCallbackServer start(String expectedState) throws IOException {
		return new LoopbackCallbackServer(
				HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0), expectedState);
	}

	public URI redirectUri() {
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + PATH);
	}

	/** `state`가 맞는 callback의 query parameter를 기다린다. */
	public Map<String, String> await(Duration timeout) {
		try {
			return this.callback.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			throw new LocalClientException("%d초 안에 login이 끝나지 않았다".formatted(timeout.toSeconds()));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new LocalClientException("callback을 기다리다 중단됐다", ex);
		}
		catch (ExecutionException ex) {
			throw new LocalClientException("callback을 처리하지 못했다", ex.getCause());
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		if (!PATH.equals(exchange.getRequestURI().getPath())) {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
			return;
		}
		Map<String, String> params = Form.decode(exchange.getRequestURI().getRawQuery());
		if (!"GET".equals(exchange.getRequestMethod()) || !this.expectedState.equals(params.get("state"))) {
			// 요청에 담긴 값은 응답에 쓰지 않는다.
			reply(exchange, 400, "이 login의 callback이 아닙니다.");
			return;
		}
		boolean first = this.callback.complete(params);
		reply(exchange, 200, first ? "login이 끝났습니다. 이 창을 닫고 terminal로 돌아가세요." : "이미 처리한 callback입니다.");
	}

	private static void reply(HttpExchange exchange, int status, String message) throws IOException {
		byte[] body = message.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	@Override
	public void close() {
		this.server.stop(0);
	}
}
