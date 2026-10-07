package dev.starryeye.cimd.authserver.cimd;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
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
 * 본문은 크기 상한보다 1 byte 더 읽으면 멈춘다. 상한을 넘었는지만 알면 되기 때문이다.
 */
public final class HttpsClientMetadataFetcher implements ClientMetadataHttp {

	private final ClientIdUrlValidator urlValidator;

	private final ClientIdMetadataDocumentProperties properties;

	private final HttpClient http;

	public HttpsClientMetadataFetcher(ClientIdUrlValidator urlValidator,
			ClientIdMetadataDocumentProperties properties, SSLContext sslContext) {
		this.urlValidator = urlValidator;
		this.properties = properties;
		this.http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(properties.connectTimeout())
				.sslContext(sslContext)
				.build();
	}

	@Override
	public FetchedDocument get(URI uri) {
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
