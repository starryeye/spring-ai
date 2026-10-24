package dev.starryeye.memory.mcpserver.config;

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
 * streamable transport의 오류 응답에서 예외 객체를 빼고, id 없는 JSON-RPC 오류({@code code}와 {@code message})만 남긴다.
 *
 * <p>Spring AI 2.0.2의 {@code WebMvcStreamableServerTransportProvider}는 처리하지 못한 요청에
 * {@code ServerResponse.status(...).body(McpError)}로 답한다.
 * Spring MVC는 이 예외 객체를 JSON bean으로 직렬화하므로, 본문에 {@code stackTrace}(class·method·line 번호)가 그대로 나간다
 * (mcp-spring-webmvc 2.0.2 source와 실행으로 확인).
 * token이 있는 사용자는 누구나 형식이 틀린 본문 하나로 이 응답을 받을 수 있다.
 * 그래서 transport가 예외 객체로 답한 오류 응답을 모두 여기서 바꾼다.
 *
 * <p>바꾸는 응답은 아래와 같다.
 * 상태 코드와 {@code code}는 transport가 정한 그대로 둔다.
 * <ul>
 *   <li>{@code 400} — JSON-RPC 메시지로 읽을 수 없는 본문, 맞지 않는 {@code Accept}, {@code Mcp-Session-Id} 없는 요청,
 *       session ID를 붙인 {@code initialize}</li>
 *   <li>{@code 404} — 없거나 만료된 session으로 온 요청</li>
 *   <li>{@code 503} — session 수가 상한에 닿은 뒤의 {@code initialize}</li>
 *   <li>{@code 500} — 그 밖에 transport가 처리하지 못한 요청</li>
 * </ul>
 * 위 응답은 POST에서 나온다.
 * DELETE에서는 session을 지우다 실패한 {@code 500}만 예외 객체로 답한다.
 *
 * <p>{@code id}는 늘 {@code null}이다.
 * transport의 오류 응답에는 요청의 {@code id}가 담기지 않고, 본문이 깨졌다면 {@code id}를 읽을 수도 없다.
 * practice의 {@code McpProtocolVersionFilter}가 내는 오류 본문과 같은 모양이다.
 *
 * <p>transport의 오류 {@code message}는 고정 문장이거나 client가 보낸 session ID를 붙인 문장이라 그대로 옮기고,
 * {@code data}는 옮기지 않는다.
 * 문자열로 답하거나 본문 없이 답한 오류(GET·DELETE의 {@code 400}·{@code 404}, 종료 중의 {@code 503})와
 * SSE 응답에는 예외 정보가 없어 그대로 둔다.
 * Spring AI를 올리면 이 가정을 다시 확인한다.
 *
 * <p>{@code McpSessionBindingFilter}가 {@code response.sendError}로 내는 {@code 403}·{@code 404}는 servlet filter의 응답이라
 * router를 지나지 않고, 이 filter도 거치지 않는다.
 * 이 응답에는 예외 객체가 없고, 본문은 Spring Boot의 오류 page가 {@code timestamp}·{@code status}·{@code error}·{@code path}만 담아 쓴다.
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
