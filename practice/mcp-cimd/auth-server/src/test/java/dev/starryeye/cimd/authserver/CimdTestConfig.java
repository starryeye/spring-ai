package dev.starryeye.cimd.authserver;

import dev.starryeye.cimd.authserver.cimd.ClientMetadataHttp;
import dev.starryeye.cimd.authserver.cimd.FetchedDocument;
import dev.starryeye.cimd.authserver.cimd.InvalidClientMetadataException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 8172에 agent가 없어도 되도록, 문서와 JWKS를 메모리에서 돌려준다.
 * 문서 주소 검사는 진짜 {@code ClientIdUrlValidator}가 그대로 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CimdTestConfig {

	@Bean
	@Primary
	ClientMetadataHttp fakeClientMetadataHttp() {
		Map<String, String> documents = Map.of(
				TestClientDocuments.CHATGPT, TestClientDocuments.chatgptDocument(),
				TestClientDocuments.CLAUDE, TestClientDocuments.claudeDocument(),
				TestClientDocuments.JWKS, TestClientDocuments.jwks());
		return uri -> {
			String body = documents.get(uri.toString());
			if (body == null) {
				throw new InvalidClientMetadataException("문서가 없다: " + uri);
			}
			return FetchedDocument.of(body.getBytes(StandardCharsets.UTF_8), "max-age=300");
		};
	}
}
