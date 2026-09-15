package dev.starryeye.memoryauthn.authserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 이 인가 서버가 토큰을 발급해 줄 수 있는 보호 리소스(MCP 서버) 목록.
 *
 * <p>RFC 8707 의 {@code resource} 는 "이 토큰을 어느 리소스에 쓸 것인가"를 말한다.
 * 인가 서버는 그 값을 그대로 믿으면 안 되고, 아는 리소스인지 확인한 뒤
 * 토큰의 {@code aud} 로 박아야 한다. 그래야 MCP 서버가 자기 앞으로 발급된
 * 토큰만 받아들일 수 있다.
 */
@ConfigurationProperties("mcp.authorization")
public record McpResourceProperties(List<String> resources) {

	/** RFC 8707 §2 의 요청 파라미터 이름. */
	public static final String RESOURCE_PARAMETER = "resource";

	/** RFC 8707 §2 가 정의한 오류 코드. */
	public static final String INVALID_TARGET = "invalid_target";

	/** 오류 응답의 error_uri 로 쓴다. */
	public static final String RFC_8707 = "https://www.rfc-editor.org/rfc/rfc8707#section-2";

	public McpResourceProperties {
		resources = (resources == null) ? List.of() : List.copyOf(resources);
	}

	/**
	 * 값이 문자열 하나이고 목록에 있을 때만 허용한다.
	 * {@code resource} 가 여러 개 오면 {@code String[]} 이 되는데,
	 * 이 practice 는 보호 리소스 하나만 다루므로 허용하지 않는다.
	 */
	public boolean isAllowed(Object resource) {
		return resource instanceof String value && this.resources.contains(value);
	}
}
