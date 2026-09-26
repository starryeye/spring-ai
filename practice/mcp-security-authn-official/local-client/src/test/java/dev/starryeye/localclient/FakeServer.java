package dev.starryeye.localclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 경로별로 정해 둔 응답을 돌려주고, 받은 요청을 기록하는 테스트용 server. */
final class FakeServer implements AutoCloseable {

	record Recorded(String method, String path, Map<String, List<String>> headers, String body) {
	}

	record Reply(int status, Map<String, String> headers, String body) {

		static Reply json(String body) {
			return new Reply(200, Map.of("Content-Type", "application/json"), body);
		}

		static Reply status(int status) {
			return new Reply(status, Map.of(), "");
		}
	}

	private final HttpServer server;

	private final Map<String, Reply> routes = new ConcurrentHashMap<>();

	final List<Recorded> requests = new CopyOnWriteArrayList<>();

	FakeServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	String origin() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	void on(String method, String path, Reply reply) {
		this.routes.put(method + " " + path, reply);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		this.requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
				Map.copyOf(exchange.getRequestHeaders()), body));
		Reply reply = this.routes.getOrDefault(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(),
				Reply.status(404));
		reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
		byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		exchange.close();
	}

	@Override
	public void close() {
		this.server.stop(0);
	}
}
