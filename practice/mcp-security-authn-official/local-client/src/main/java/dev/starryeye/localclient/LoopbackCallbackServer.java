package dev.starryeye.localclient;

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
 * <p>`127.0.0.1`의 비어 있는 포트를 운영체제에서 받는다. Authorization Server는 loopback redirect URI의
 * 포트를 비교하지 않으므로, 등록된 `http://127.0.0.1:8123/callback`과 포트가 달라도 된다(RFC 8252 7.3절).
 * 처음 받은 callback만 결과로 쓰고, `/callback`이 아닌 경로(`/favicon.ico` 등)는 404다.
 */
public final class LoopbackCallbackServer implements AutoCloseable {

	private static final String PATH = "/callback";

	private final HttpServer server;

	private final CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();

	private LoopbackCallbackServer(HttpServer server) {
		this.server = server;
		this.server.createContext(PATH, this::handle);
		this.server.start();
	}

	public static LoopbackCallbackServer start() throws IOException {
		return new LoopbackCallbackServer(
				HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0));
	}

	public URI redirectUri() {
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + PATH);
	}

	/** callback의 query parameter를 기다린다. */
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
		boolean first = this.callback.complete(Form.decode(exchange.getRequestURI().getRawQuery()));
		byte[] body = (first ? "login이 끝났습니다. 이 창을 닫고 terminal로 돌아가세요." : "이미 처리한 callback입니다.")
				.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	@Override
	public void close() {
		this.server.stop(0);
	}
}
