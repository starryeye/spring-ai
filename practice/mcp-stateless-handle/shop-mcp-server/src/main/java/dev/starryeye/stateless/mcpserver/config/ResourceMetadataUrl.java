package dev.starryeye.stateless.mcpserver.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * RFC 9728 §3.1의 규칙대로, protected resource URL의 경로 앞에
 * {@code /.well-known/oauth-protected-resource}를 끼워 넣은 URL을 만든다.
 * 예를 들어 {@code /mcp} 요청은 {@code /.well-known/oauth-protected-resource/mcp}를 가리킨다.
 * {@code 401}과 {@code 403}의 {@code resource_metadata}가 같은 값을 쓴다.
 */
public final class ResourceMetadataUrl {

	private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";

	private ResourceMetadataUrl() {
	}

	public static String of(HttpServletRequest request) {
		String path = request.getRequestURI();
		return UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
				.replacePath(PROTECTED_RESOURCE_METADATA + ("/".equals(path) ? "" : path))
				.replaceQuery(null)
				.build()
				.toUriString();
	}
}
