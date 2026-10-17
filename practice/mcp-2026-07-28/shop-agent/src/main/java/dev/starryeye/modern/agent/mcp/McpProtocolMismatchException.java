package dev.starryeye.modern.agent.mcp;

/**
 * 이 client와 MCP Server가 같은 2026-07-28 요청 형식으로 말하지 않는다.
 *
 * <p>두 경우가 있다.
 * 하나는 {@code server/discover}의 {@code supportedVersions}에 {@code 2026-07-28}이 없을 때다.
 * 다른 하나는 MCP Server가 header나 {@code _meta}를 받지 않을 때다.
 * header와 본문이 다르면 {@code -32020}, 필요한 client capability가 없으면 {@code -32021},
 * 모르는 버전이면 {@code -32022}다.
 * 뒤의 경우는 이 client가 요청을 잘못 만든 것이라, 같은 요청을 다시 보내도 결과가 같다.
 */
public class McpProtocolMismatchException extends ModernMcpException {

	public McpProtocolMismatchException(int httpStatus, Integer jsonRpcCode, String message) {
		super(httpStatus, jsonRpcCode, message);
	}

	/** header와 본문 불일치, 빠진 client capability, 모르는 버전이다. */
	static boolean matches(int code) {
		return code == -32020 || code == -32021 || code == -32022;
	}
}
