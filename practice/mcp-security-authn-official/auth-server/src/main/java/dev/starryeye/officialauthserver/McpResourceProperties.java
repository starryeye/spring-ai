package dev.starryeye.officialauthserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 이 Authorization Server가 token을 발급해 줄 수 있는 protected resource(MCP Server) 목록이다.
 *
 * <p>RFC 8707의 {@code resource}는 "이 token을 어느 resource에 쓸 것인가"를 말한다.
 * Authorization Server는 그 값을 그대로 믿지 않고, 아는 resource인지 먼저 확인한다.
 * 확인한 값은 token의 {@code aud}에 넣는다.
 * 그래야 MCP Server가 자기에게 발급된 token만 받을 수 있다.
 */
@ConfigurationProperties("mcp.authorization")
public record McpResourceProperties(List<String> resources) {

	/** RFC 8707 §2의 요청 parameter 이름이다. */
	public static final String RESOURCE_PARAMETER = "resource";

	/** RFC 8707 §2가 정의한 오류 코드다. */
	public static final String INVALID_TARGET = "invalid_target";

	/** 오류 응답의 error_uri로 쓴다. */
	public static final String RFC_8707 = "https://www.rfc-editor.org/rfc/rfc8707#section-2";

	public McpResourceProperties {
		resources = (resources == null) ? List.of() : List.copyOf(resources);
	}

	/**
	 * 값이 문자열 하나이고 목록에 있을 때만 허용한다.
	 * {@code resource}가 여러 개 오면 값이 {@code String[]}이 된다.
	 * 이 practice는 protected resource 하나만 다루므로 이 경우를 허용하지 않는다.
	 */
	public boolean isAllowed(Object resource) {
		return resource instanceof String value && this.resources.contains(value);
	}
}
