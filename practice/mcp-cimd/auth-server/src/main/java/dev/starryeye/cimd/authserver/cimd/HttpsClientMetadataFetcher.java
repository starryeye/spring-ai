package dev.starryeye.cimd.authserver.cimd;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * client가 고른 주소에서 JSON 문서를 가져온다.
 *
 * <p>주소는 client가 정하므로, 요청하기 전에 {@link ClientIdUrlValidator}로 다시 검사한다.
 * redirect를 따라가면 검사한 주소와 다른 곳(내부망 포함)으로 갈 수 있어 따라가지 않는다.
 * 큰 문서와 느린 응답으로 서버 자원을 묶지 못하게 크기와 시간을 제한한다(CIMD draft §6.5, §6.6).
 *
 * <p>시간 제한은 header와 본문을 합친 응답 전체에 하나만 둔다.
 * header만 빨리 보내고 본문을 조금씩 흘리는 응답도 막기 위해서다.
 * 제한을 넘기면 요청을 취소해 연결을 닫는다.
 * 본문은 크기 상한보다 1 byte 더 읽으면 멈춘다.
 * 상한을 넘었는지만 알면 되기 때문이다.
 *
 * <p>문서는 login 전의 authorization request에서도 가져오고, JWKS는 인증 전의 token request마다 가져온다.
 * 그래서 인증 없는 요청이 이 서버를 시켜 바깥으로 요청을 보내게 할 수 있다(CIMD draft §6.5).
 * 두 가지로 그 양을 줄인다.
 * 같은 주소를 가져오는 중에 같은 주소가 또 오면 새로 요청하지 않고 진행 중인 결과를 함께 기다린다(single-flight).
 * 결과는 그 가져오기가 끝나면 버리므로, 실패한 결과를 cache하지 않는 규칙(CIMD §4.4)과 부딪치지 않는다.
 * 그리고 동시에 진행하는 가져오기 수에 상한({@code mcp.cimd.max-concurrent-fetches})을 둔다.
 * 자리가 없으면 기다리지 않고 바로 실패한다.
 * 기다리게 하면 그동안 요청 thread가 묶이기 때문이다.
 * 이 상한은 서버 전체에 하나라서, 상한을 채우는 쪽이 있으면 다른 client의 문서도 그동안 가져오지 못한다.
 * client나 원격 주소별 속도 제한은 이 practice에 없다.
 */
public final class HttpsClientMetadataFetcher implements ClientMetadataHttp {

	private final ClientIdUrlValidator urlValidator;

	private final ClientIdMetadataDocumentProperties properties;

	private final HttpClient http;

	private final Semaphore fetchPermits;

	/**
	 * 진행 중인 가져오기다.
	 * 끝나면 지운다.
	 */
	private final Map<URI, CompletableFuture<FetchedDocument>> inFlight = new ConcurrentHashMap<>();

	public HttpsClientMetadataFetcher(ClientIdUrlValidator urlValidator,
			ClientIdMetadataDocumentProperties properties, SSLContext sslContext) {
		this.urlValidator = urlValidator;
		this.properties = properties;
		this.http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(properties.connectTimeout())
				.sslContext(sslContext)
				.build();
		this.fetchPermits = new Semaphore(properties.maxConcurrentFetches());
	}

	@Override
	public FetchedDocument get(URI uri) {
		CompletableFuture<FetchedDocument> mine = new CompletableFuture<>();
		CompletableFuture<FetchedDocument> running = this.inFlight.putIfAbsent(uri, mine);
		if (running != null) {
			return awaitRunning(running, uri);
		}
		try {
			FetchedDocument document = fetchWithinLimit(uri);
			mine.complete(document);
			return document;
		}
		catch (RuntimeException | Error ex) {
			mine.completeExceptionally(ex);
			throw ex;
		}
		finally {
			this.inFlight.remove(uri, mine);
		}
	}

	/**
	 * 같은 주소의 진행 중인 가져오기를 기다린다.
	 * 그 가져오기는 host 조회와 응답 시간 제한 안에 끝나므로, 그 두 시간을 합친 만큼만 기다린다.
	 */
	private FetchedDocument awaitRunning(CompletableFuture<FetchedDocument> running, URI uri) {
		Duration wait = this.properties.connectTimeout().plus(this.properties.readTimeout()).plusSeconds(1);
		try {
			return running.get(wait.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (ExecutionException ex) {
			throw new InvalidClientMetadataException(ex.getCause().getMessage(), ex.getCause());
		}
		catch (TimeoutException ex) {
			throw new InvalidClientMetadataException("같은 주소의 가져오기를 %s 기다렸지만 끝나지 않았다: %s".formatted(wait, uri), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new InvalidClientMetadataException("문서를 가져오지 못했다: " + uri, ex);
		}
	}

	private FetchedDocument fetchWithinLimit(URI uri) {
		if (!this.fetchPermits.tryAcquire()) {
			throw new InvalidClientMetadataException(
					"동시에 가져오는 문서가 상한(%d)에 닿았다: %s".formatted(this.properties.maxConcurrentFetches(), uri));
		}
		try {
			return fetch(uri);
		}
		finally {
			this.fetchPermits.release();
		}
	}

	private FetchedDocument fetch(URI uri) {
		this.urlValidator.validate(uri.toString());
		HttpRequest request = HttpRequest.newBuilder(uri)
				.header("Accept", "application/json")
				.GET()
				.build();
		int limit = this.properties.maxDocumentBytes();
		CompletableFuture<HttpResponse<byte[]>> future = this.http.sendAsync(request,
				info -> new BoundedBodySubscriber(limit + 1));
		HttpResponse<byte[]> response;
		try {
			response = future.get(this.properties.readTimeout().toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw new InvalidClientMetadataException(
					"문서를 가져오지 못했다(%s 안에 응답을 다 받지 못했다): %s".formatted(this.properties.readTimeout(), uri), ex);
		}
		catch (ExecutionException ex) {
			throw new InvalidClientMetadataException("문서를 가져오지 못했다: " + uri, ex.getCause());
		}
		catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new InvalidClientMetadataException("문서를 가져오지 못했다: " + uri, ex);
		}
		if (response.statusCode() != 200) {
			throw new InvalidClientMetadataException("문서 응답이 200이 아니다(%d): %s".formatted(response.statusCode(), uri));
		}
		String contentType = response.headers().firstValue("Content-Type").orElse("");
		if (!isJson(contentType)) {
			throw new InvalidClientMetadataException("문서의 Content-Type이 JSON이 아니다(%s): %s".formatted(contentType, uri));
		}
		byte[] bytes = response.body();
		if (bytes.length > limit) {
			throw new InvalidClientMetadataException("문서가 %d byte를 넘는다: %s".formatted(limit, uri));
		}
		return FetchedDocument.of(bytes, response.headers().firstValue("Cache-Control").orElse(null));
	}

	static boolean isJson(String contentType) {
		String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
		return "application/json".equals(type) || (type.startsWith("application/") && type.endsWith("+json"));
	}

	/**
	 * 본문을 {@code capacity} byte까지만 모으고, 채우면 구독을 취소해 나머지를 받지 않는다.
	 */
	private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

		private final int capacity;

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		private final CompletableFuture<byte[]> result = new CompletableFuture<>();

		private Flow.Subscription subscription;

		BoundedBodySubscriber(int capacity) {
			this.capacity = capacity;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			if (this.result.isDone()) {
				return;
			}
			for (ByteBuffer item : items) {
				byte[] chunk = new byte[Math.min(item.remaining(), this.capacity - this.buffer.size())];
				item.get(chunk);
				this.buffer.write(chunk, 0, chunk.length);
			}
			if (this.buffer.size() >= this.capacity) {
				this.subscription.cancel();
				this.result.complete(this.buffer.toByteArray());
			}
		}

		@Override
		public void onError(Throwable throwable) {
			this.result.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			this.result.complete(this.buffer.toByteArray());
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return this.result;
		}
	}
}
