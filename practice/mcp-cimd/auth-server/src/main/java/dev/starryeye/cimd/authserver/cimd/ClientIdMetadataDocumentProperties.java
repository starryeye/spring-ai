package dev.starryeye.cimd.authserver.cimd;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Authorization Server가 CIMD 문서를 가져오고 믿는 정책이다. 모든 client에 똑같이 적용한다.
 *
 * <p>client별 설정은 없다. client에 대한 정보는 모두 그 client가 올린 문서에서 온다.
 *
 * @param loopbackException loopback 주소여도 가져오는 주소 하나(scheme·host·port). 학습 환경의 agent 문서 host다
 * @param maxDocumentBytes 문서 크기 상한. CIMD draft는 5KB를 권한다
 * @param connectTimeout 문서 host에 연결하는 시간 제한
 * @param readTimeout 응답 전체(header와 본문)를 받는 시간 제한. 본문을 조금씩 흘리는 응답도 이 안에 끊는다
 * @param defaultCacheTtl 응답에 {@code Cache-Control}이 없을 때 cache하는 기간
 * @param maxCacheTtl {@code max-age}가 길어도 넘지 않는 cache 기간
 * @param trustBundle 문서 host의 인증서를 믿을 때 쓰는 Spring Boot SSL bundle 이름. 비우면 JVM 기본 truststore를 쓴다
 * @param scopes CIMD client가 요청할 수 있는 scope. 문서의 {@code scope}는 읽지 않고 이 서버의 정책으로 정한다
 */
@ConfigurationProperties("mcp.cimd")
public record ClientIdMetadataDocumentProperties(String loopbackException, Integer maxDocumentBytes,
		Duration connectTimeout, Duration readTimeout, Duration defaultCacheTtl, Duration maxCacheTtl,
		String trustBundle, List<String> scopes) {

	public ClientIdMetadataDocumentProperties {
		maxDocumentBytes = (maxDocumentBytes == null) ? 5120 : maxDocumentBytes;
		connectTimeout = (connectTimeout == null) ? Duration.ofSeconds(2) : connectTimeout;
		readTimeout = (readTimeout == null) ? Duration.ofSeconds(3) : readTimeout;
		defaultCacheTtl = (defaultCacheTtl == null) ? Duration.ofMinutes(5) : defaultCacheTtl;
		maxCacheTtl = (maxCacheTtl == null) ? Duration.ofHours(1) : maxCacheTtl;
		// 설정 파일에 `trust-bundle:`만 쓰면 빈 문자열이 들어온다. 이때도 정하지 않은 것으로 본다.
		trustBundle = (trustBundle == null || trustBundle.isBlank()) ? null : trustBundle;
		scopes = (scopes == null || scopes.isEmpty())
				? List.of("openid", "products:read", "products:write", "orders:write") : List.copyOf(scopes);
	}
}
