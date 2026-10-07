package dev.starryeye.cimd.agent.cimd;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * client 문서와 JWKS를 {@code https}로 올리는 작은 서버다.
 *
 * <p>CIMD의 client_id는 {@code https} 주소여야 하고 localhost 예외가 없다.
 * 실제 제품에서는 공개 web site가 이 일을 한다(ChatGPT는 {@code https://chatgpt.com/oauth/client.json}).
 * 채팅 화면(8170, {@code http})과 다른 web site라는 점이 보이도록 같은 process 안에 따로 연다.
 * 이 기기 안에서만 접속할 수 있게 127.0.0.1에만 bind한다.
 */
public final class ClientMetadataServer implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(ClientMetadataServer.class);

	private final int port;

	private final SSLContext sslContext;

	private final Map<String, String> bodies;

	private HttpsServer server;

	public ClientMetadataServer(int port, SSLContext sslContext, ClientMetadataDocuments documents) {
		this.port = port;
		this.sslContext = sslContext;
		this.bodies = Map.of(
				ClientType.CHATGPT.path(), documents.document(ClientType.CHATGPT),
				ClientType.CLAUDE.path(), documents.document(ClientType.CLAUDE),
				ClientMetadataDocuments.JWKS_PATH, documents.jwks());
	}

	@Override
	public synchronized void start() {
		try {
			HttpsServer created = HttpsServer.create(
					new InetSocketAddress(InetAddress.getByName("127.0.0.1"), this.port), 0);
			created.setHttpsConfigurator(new HttpsConfigurator(this.sslContext));
			created.createContext("/", this::handle);
			created.start();
			this.server = created;
			log.info("client 문서와 JWKS를 올렸다 (https://localhost:{}{}, {}, {})", port(), ClientType.CHATGPT.path(),
					ClientType.CLAUDE.path(), ClientMetadataDocuments.JWKS_PATH);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("client 문서 서버를 열지 못했다 (포트 " + this.port + ")", ex);
		}
	}

	@Override
	public synchronized void stop() {
		if (this.server != null) {
			this.server.stop(0);
			this.server = null;
		}
	}

	@Override
	public synchronized boolean isRunning() {
		return this.server != null;
	}

	/** 실제로 연 포트다. 설정이 0이면 OS가 고른 포트다. */
	public synchronized int port() {
		return address().getPort();
	}

	public synchronized InetSocketAddress address() {
		return this.server.getAddress();
	}

	private void handle(HttpExchange exchange) throws IOException {
		try {
			String body = this.bodies.get(exchange.getRequestURI().getPath());
			if (body == null) {
				exchange.sendResponseHeaders(404, -1);
				return;
			}
			if (!"GET".equals(exchange.getRequestMethod())) {
				exchange.getResponseHeaders().set("Allow", "GET");
				exchange.sendResponseHeaders(405, -1);
				return;
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			// Authorization Server는 이 값만큼 문서를 cache한다. 문서를 바꾸면 길어야 5분 뒤에 반영된다.
			exchange.getResponseHeaders().set("Cache-Control", "max-age=300");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		finally {
			exchange.close();
		}
	}
}
