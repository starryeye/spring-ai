package dev.starryeye.cimd.authserver.cimd;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class HttpsClientMetadataFetcherTest {

	static final String DOCUMENT = "{\"client_id\":\"x\"}";

	HttpsServer server;

	ExecutorService executor;

	String origin;

	AtomicInteger redirectTargetHits = new AtomicInteger();

	CountDownLatch trickleWriteFailed = new CountDownLatch(1);

	/** 기본 시간 제한으로 가져온다. 정상 응답을 받는 테스트는 느린 환경에서도 흔들리지 않아야 한다. */
	HttpsClientMetadataFetcher fetcher;

	/** 응답 전체를 0.5초 안에 받아야 한다. 느린 응답을 끊는 테스트만 쓴다. */
	HttpsClientMetadataFetcher shortTimeoutFetcher;

	@BeforeEach
	void setUp() throws Exception {
		this.server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.server.setHttpsConfigurator(new HttpsConfigurator(TestTls.server()));
		this.executor = Executors.newCachedThreadPool();
		this.server.setExecutor(this.executor);
		this.server.createContext("/ok", exchange -> send(exchange, 200, "application/json", "max-age=300", DOCUMENT));
		this.server.createContext("/plus-json", exchange -> send(exchange, 200, "application/client-metadata+json; charset=utf-8", null, DOCUMENT));
		this.server.createContext("/big", exchange -> send(exchange, 200, "application/json", null, "x".repeat(6000)));
		this.server.createContext("/text", exchange -> send(exchange, 200, "text/plain", null, DOCUMENT));
		this.server.createContext("/missing", exchange -> send(exchange, 404, "application/json", null, "{}"));
		this.server.createContext("/redirect", exchange -> {
			exchange.getResponseHeaders().set("Location", "/target");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		this.server.createContext("/target", exchange -> {
			this.redirectTargetHits.incrementAndGet();
			send(exchange, 200, "application/json", null, DOCUMENT);
		});
		this.server.createContext("/slow", exchange -> {
			try {
				Thread.sleep(2000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			send(exchange, 200, "application/json", null, DOCUMENT);
		});
		this.server.createContext("/trickle", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, 0);
			try {
				OutputStream out = exchange.getResponseBody();
				for (int i = 0; i < 60; i++) {
					out.write('x');
					out.flush();
					Thread.sleep(150);
				}
			}
			catch (IOException ex) {
				this.trickleWriteFailed.countDown();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			finally {
				exchange.close();
			}
		});
		this.server.start();
		this.origin = "https://localhost:" + this.server.getAddress().getPort();

		ClientIdUrlValidator validator = new ClientIdUrlValidator(this.origin, HostResolver.SYSTEM);
		this.fetcher = new HttpsClientMetadataFetcher(validator,
				new ClientIdMetadataDocumentProperties(this.origin, 5120, null, null, null, null, null, null),
				TestTls.client());
		this.shortTimeoutFetcher = new HttpsClientMetadataFetcher(validator,
				new ClientIdMetadataDocumentProperties(this.origin, 5120, null, Duration.ofMillis(500), null, null, null,
						null),
				TestTls.client());
	}

	@AfterEach
	void tearDown() {
		this.server.stop(0);
		this.executor.shutdownNow();
	}

	static void send(HttpExchange exchange, int status, String contentType, String cacheControl, String body)
			throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", contentType);
		if (cacheControl != null) {
			exchange.getResponseHeaders().set("Cache-Control", cacheControl);
		}
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	@Test
	void 문서와_cache_header를_가져온다() {
		FetchedDocument document = this.fetcher.get(URI.create(this.origin + "/ok"));

		assertThat(new String(document.body(), StandardCharsets.UTF_8)).isEqualTo(DOCUMENT);
		assertThat(document.maxAge()).isEqualTo(Duration.ofSeconds(300));
	}

	@Test
	void plus_json_Content_Type도_JSON으로_받는다() {
		assertThat(this.fetcher.get(URI.create(this.origin + "/plus-json")).body()).isNotEmpty();
	}

	@Test
	void 크기_상한을_넘으면_거절한다() {
		거절("/big", "5120");
	}

	@Test
	void JSON이_아닌_Content_Type은_거절한다() {
		거절("/text", "Content-Type");
	}

	@Test
	void 응답이_200이_아니면_거절한다() {
		거절("/missing", "404");
	}

	@Test
	void redirect_응답은_따라가지_않는다() {
		거절("/redirect", "302");
		assertThat(this.redirectTargetHits).hasValue(0);
	}

	@Test
	void 응답이_늦으면_거절한다() {
		assertThatExceptionOfType(InvalidClientMetadataException.class)
				.isThrownBy(() -> this.shortTimeoutFetcher.get(URI.create(this.origin + "/slow")))
				.withMessageContaining("가져오지 못했다")
				.withCauseInstanceOf(TimeoutException.class);
	}

	@Test
	void header를_빨리_보내도_본문이_늦으면_시간_제한_안에_끊는다() throws Exception {
		long start = System.nanoTime();

		assertThatExceptionOfType(InvalidClientMetadataException.class)
				.isThrownBy(() -> this.shortTimeoutFetcher.get(URI.create(this.origin + "/trickle")))
				.withMessageContaining("가져오지 못했다")
				.withCauseInstanceOf(TimeoutException.class);

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
		// 요청을 취소하면 연결이 닫히므로 server가 더 쓰려다 실패한다
		assertThat(this.trickleWriteFailed.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void 주소_규칙을_어기면_요청하지_않는다() {
		assertThatExceptionOfType(InvalidClientMetadataException.class)
				.isThrownBy(() -> this.fetcher.get(URI.create("https://localhost:1/oauth/client.json")))
				.withMessageContaining("내부 주소");
	}

	void 거절(String path, String reason) {
		assertThatExceptionOfType(InvalidClientMetadataException.class)
				.isThrownBy(() -> this.fetcher.get(URI.create(this.origin + path)))
				.withMessageContaining(reason);
	}
}
