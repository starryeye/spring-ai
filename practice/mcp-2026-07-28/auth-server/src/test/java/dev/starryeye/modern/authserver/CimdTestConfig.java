package dev.starryeye.modern.authserver;

import dev.starryeye.modern.authserver.cimd.ClientMetadataHttp;
import dev.starryeye.modern.authserver.cimd.FetchedDocument;
import dev.starryeye.modern.authserver.cimd.InvalidClientMetadataException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 8182에 agent가 없어도 되도록, 문서와 JWKS를 메모리에서 돌려준다.
 * 문서 주소 검사는 진짜 {@code ClientIdUrlValidator}가 그대로 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CimdTestConfig {

	@Bean
	@Primary
	TestDocumentHost fakeClientMetadataHttp() {
		return new TestDocumentHost();
	}

	/**
	 * 고정 문서(ChatGPT형, Claude형, JWKS)는 {@code max-age=300}으로 돌려준다.
	 * 테스트가 {@link #publish}로 올린 문서는 {@code no-store}로 돌려줘서, 문서를 바꾸면 다음 요청부터 바로 보인다.
	 */
	public static final class TestDocumentHost implements ClientMetadataHttp {

		private final Map<String, String> fixed = Map.of(
				TestClientDocuments.CHATGPT, TestClientDocuments.chatgptDocument(),
				TestClientDocuments.CLAUDE, TestClientDocuments.claudeDocument(),
				TestClientDocuments.JWKS, TestClientDocuments.jwks());

		private final Map<String, String> published = new ConcurrentHashMap<>();

		public void publish(String url, String body) {
			this.published.put(url, body);
		}

		public void withdraw(String url) {
			this.published.remove(url);
		}

		@Override
		public FetchedDocument get(URI uri) {
			String body = this.published.get(uri.toString());
			if (body != null) {
				return FetchedDocument.of(body.getBytes(StandardCharsets.UTF_8), "no-store");
			}
			body = this.fixed.get(uri.toString());
			if (body == null) {
				throw new InvalidClientMetadataException("문서가 없다: " + uri);
			}
			return FetchedDocument.of(body.getBytes(StandardCharsets.UTF_8), "max-age=300");
		}
	}
}
