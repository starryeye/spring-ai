package dev.starryeye.authz.mcpserver.config;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.util.Assert;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * 이 서버가 지키는 resource와 그 PRM 주소를 만든다(RFC 9728).
 * PRM의 {@code resource}, {@code 401}과 {@code 403}의 {@code resource_metadata}가 이 값을 쓴다.
 *
 * <p>요청의 {@code Host}가 아니라 설정한 주소({@code audiences}) 하나에서 만든다.
 * 요청 URL로 만들면 {@code Host}를 바꾼 요청에 다른 서버 주소를 광고하고, reverse proxy 뒤에서는 scheme도 틀린다.
 * token의 {@code aud}로 검증하는 값과 같은 하나를 쓰면 셋이 어긋날 일이 없다.
 */
public final class ResourceMetadataUrl {

	private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";

	private ResourceMetadataUrl() {
	}

	/**
	 * 이 서버가 지키는 resource의 식별자다. token의 {@code aud}로 검증하는 값({@code audiences})과 같다.
	 * 이 서버의 resource는 MCP endpoint 하나라서 값도 하나여야 한다.
	 */
	public static String resource(OAuth2ResourceServerProperties properties) {
		List<String> audiences = properties.getJwt().getAudiences();
		Assert.state(audiences.size() == 1,
				"spring.security.oauth2.resourceserver.jwt.audiences에는 이 MCP Server의 주소 하나만 둔다: " + audiences);
		return audiences.get(0);
	}

	/**
	 * RFC 9728 §3.1의 규칙대로, resource URL의 경로 앞에 {@code /.well-known/oauth-protected-resource}를 끼워 넣는다.
	 * 예를 들어 {@code https://mcp.example.com/mcp}는 {@code https://mcp.example.com/.well-known/oauth-protected-resource/mcp}다.
	 */
	public static String of(String resource) {
		UriComponents uri = UriComponentsBuilder.fromUriString(resource).build();
		String path = (uri.getPath() == null || "/".equals(uri.getPath())) ? "" : uri.getPath();
		return UriComponentsBuilder.fromUriString(resource)
				.replacePath(PROTECTED_RESOURCE_METADATA + path)
				.replaceQuery(null)
				.build()
				.toUriString();
	}
}
