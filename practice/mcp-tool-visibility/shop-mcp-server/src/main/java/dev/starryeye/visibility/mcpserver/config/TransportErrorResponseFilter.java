package dev.starryeye.visibility.mcpserver.config;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * stateless transport의 오류 응답에서 예외 객체를 빼고, id 없는 JSON-RPC 오류({@code code}와 {@code message})만 남긴다.
 *
 * <p>Spring AI 2.0.2의 {@code WebMvcStatelessServerTransport}는 처리하지 못한 요청에
 * {@code ServerResponse.status(...).body(McpError)}로 답한다. Spring MVC는 이 예외 객체를 JSON bean으로 직렬화하므로,
 * 본문에 {@code stackTrace}(class·method·line 번호)가 그대로 나간다(mcp-spring-webmvc 2.0.2 source와 실행으로 확인).
 * token이 있는 사용자는 누구나 형식이 틀린 본문 하나로 이 응답을 받을 수 있다.
 * {@code tools/call}의 형식 오류는 {@code ToolScopeFilter}가 먼저 막지만, 다른 method의 params나 JSON-RPC 틀(id, jsonrpc)이
 * 틀린 경우까지 모두 앞에서 거르기는 어렵다. 그래서 transport가 내는 오류 응답 전체를 여기서 한 번 더 감싼다.
 *
 * <p>상태 코드는 transport가 정한 그대로 둔다. transport의 오류 {@code message}는 고정 문장이라 내부 정보가 없어 그대로 옮기고,
 * {@code data}는 옮기지 않는다. Spring AI를 올리면 이 가정을 다시 확인한다.
 */
final class TransportErrorResponseFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

	private final JsonMapper jsonMapper;

	TransportErrorResponseFilter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
		ServerResponse response = next.handle(request);
		if (!(response instanceof EntityResponse<?> entity) || !(entity.entity() instanceof McpError error)) {
			return response;
		}
		McpSchema.JSONRPCResponse.JSONRPCError rpc = error.getJsonRpcError();
		ObjectNode body = this.jsonMapper.createObjectNode();
		body.put("jsonrpc", "2.0");
		body.putNull("id");
		ObjectNode detail = body.putObject("error");
		detail.put("code", (rpc != null && rpc.code() != null) ? rpc.code() : McpSchema.ErrorCodes.INTERNAL_ERROR);
		detail.put("message", (rpc != null && rpc.message() != null) ? rpc.message() : "Internal error");
		return ServerResponse.status(response.statusCode())
				.contentType(MediaType.APPLICATION_JSON)
				.body(this.jsonMapper.writeValueAsString(body));
	}
}
