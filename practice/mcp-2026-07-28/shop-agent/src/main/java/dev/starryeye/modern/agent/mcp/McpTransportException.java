package dev.starryeye.modern.agent.mcp;

/**
 * MCP Server의 응답을 받지 못했다.
 *
 * <p>연결이 거절되었거나, 정한 시간 안에 답이 오지 않았거나, 응답을 읽다 연결이 끊긴 경우다.
 * HTTP 응답이 없으므로 status는 0이고 JSON-RPC {@code code}도 없다.
 * tool 호출 중에 나면 다른 MCP 오류처럼 이 문장이 모델에게 tool 결과로 간다.
 * 그래야 MCP Server가 멈춰도 채팅이 오류로 끊기지 않고, 모델이 사용자에게 사정을 알릴 수 있다.
 */
public class McpTransportException extends ModernMcpException {

	public McpTransportException(String message, Throwable cause) {
		super(0, null, message, cause);
	}
}
