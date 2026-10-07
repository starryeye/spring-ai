package dev.starryeye.cimd.authserver.cimd;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;

/**
 * client가 고른 주소에서 JSON 문서를 가져온다.
 *
 * <p>주소는 client가 정하므로, 요청하기 전에 {@link ClientIdUrlValidator}로 다시 검사한다.
 * redirect를 따라가면 검사한 주소와 다른 곳(내부망 포함)으로 갈 수 있어 따라가지 않는다.
 * 큰 문서와 느린 응답으로 서버 자원을 묶지 못하게 크기와 시간을 제한한다(CIMD draft §6.5, §6.6).
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
				.timeout(this.properties.readTimeout())
				.header("Accept", "application/json")
				.GET()
				.build();
		HttpResponse<InputStream> response;
		try {
			response = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (IOException ex) {
			throw new InvalidClientMetadataException("문서를 가져오지 못했다: " + uri, ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new InvalidClientMetadataException("문서를 가져오지 못했다: " + uri, ex);
		}
		try (InputStream body = response.body()) {
			if (response.statusCode() != 200) {
				throw new InvalidClientMetadataException("문서 응답이 200이 아니다(%d): %s".formatted(response.statusCode(), uri));
			}
			String contentType = response.headers().firstValue("Content-Type").orElse("");
			if (!isJson(contentType)) {
				throw new InvalidClientMetadataException("문서의 Content-Type이 JSON이 아니다(%s): %s".formatted(contentType, uri));
			}
			int limit = this.properties.maxDocumentBytes();
			byte[] bytes = body.readNBytes(limit + 1);
			if (bytes.length > limit) {
				throw new InvalidClientMetadataException("문서가 %d byte를 넘는다: %s".formatted(limit, uri));
			}
			return FetchedDocument.of(bytes, response.headers().firstValue("Cache-Control").orElse(null));
		}
		catch (IOException ex) {
			throw new InvalidClientMetadataException("문서를 읽지 못했다: " + uri, ex);
		}
	}

	static boolean isJson(String contentType) {
		String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
		return "application/json".equals(type) || (type.startsWith("application/") && type.endsWith("+json"));
	}
}
