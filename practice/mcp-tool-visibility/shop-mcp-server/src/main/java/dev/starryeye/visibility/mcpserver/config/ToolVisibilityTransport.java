package dev.starryeye.visibility.mcpserver.config;

import dev.starryeye.visibility.mcpserver.security.McpCaller;
import dev.starryeye.visibility.mcpserver.tool.ToolVisibility;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * SDK server와 WebMvc transport 사이에 끼어, {@code tools/list} 결과를 사용자 역할로 거른다(안내서 12장).
 *
 * <p>SDK와 Spring AI에는 사용자마다 목록을 거르는 hook이 없다.
 * {@code WebMvcStatelessServerTransport}는 {@code final}이라 상속할 수 없어서 감싼다.
 * server가 생성될 때 넘기는 handler를 감싸, 결과가 JSON으로 바뀌기 전에 {@code ListToolsResult}를 거른다.
 *
 * <p>숨긴 tool의 {@code tools/call}도 여기서 답한다.
 * tool spec을 감싸면 SDK가 입력 검증을 먼저 돌려서 그런 tool이 있다는 사실을 알게 되므로,
 * SDK가 tool을 찾기 전에 handler에서 답한다.
 * 답은 SDK가 정말 없는 tool에 주는 오류와 같아서, 호출한 쪽은 숨긴 tool과 없는 tool을 구별할 수 없다.
 */
public final class ToolVisibilityTransport implements McpStatelessServerTransport {

	private static final Logger log = LoggerFactory.getLogger(ToolVisibilityTransport.class);

	/**
	 * SDK 2.0.1이 없는 tool에 주는 message다(이름은 넣지 않는 고정 문자열이다).
	 * 숨긴 tool도 이 값으로 답해야 정말 없는 tool과 구별되지 않는다.
	 * SDK를 올리면 다시 확인한다.
	 */
	static final String UNKNOWN_TOOL_MESSAGE = "Unknown tool: invalid_tool_name";

	private final McpStatelessServerTransport delegate;

	private final ToolVisibility visibility;

	private final McpJsonMapper jsonMapper;

	public ToolVisibilityTransport(McpStatelessServerTransport delegate, ToolVisibility visibility,
			McpJsonMapper jsonMapper) {
		this.delegate = delegate;
		this.visibility = visibility;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void setMcpHandler(McpStatelessServerHandler handler) {
		this.delegate.setMcpHandler(new Handler(handler));
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.delegate.closeGracefully();
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	@Override
	public List<String> protocolVersions() {
		return this.delegate.protocolVersions();
	}

	private final class Handler implements McpStatelessServerHandler {

		private final McpStatelessServerHandler sdk;

		Handler(McpStatelessServerHandler sdk) {
			this.sdk = sdk;
		}

		@Override
		public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
				McpSchema.JSONRPCRequest request) {
			String subject = McpCaller.subject(context).orElse(null);
			if (McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
				String name = toolName(request.params());
				if (name != null && visibility.hidden(subject, name)) {
					// 받을 수 없는 권한의 tool이라 step-up하지 않는다. 있다는 사실도 알리지 않는다.
					log.info("숨긴 tool 호출 — 사용자={}, 역할={}, tool={}", subject, visibility.roleOf(subject), name);
					return Mono.just(McpSchema.JSONRPCResponse.error(request.id(),
							new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_PARAMS,
									UNKNOWN_TOOL_MESSAGE, "Tool not found: " + name)));
				}
			}
			Mono<McpSchema.JSONRPCResponse> response = this.sdk.handleRequest(context, request);
			if (!McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
				return response;
			}
			return response.map(r -> {
				if (!(r.result() instanceof McpSchema.ListToolsResult list)) {
					return r;
				}
				List<McpSchema.Tool> visible = visibility.visible(subject, list.tools());
				log.info("tools/list — 사용자={}, 역할={}, 보인 tool={}/{}", subject, visibility.roleOf(subject),
						visible.size(), list.tools().size());
				return McpSchema.JSONRPCResponse.result(r.id(), McpSchema.ListToolsResult.builder(visible)
						.nextCursor(list.nextCursor())
						.meta(list.meta())
						.build());
			});
		}

		/**
		 * SDK server가 쓰는 mapper로 params에서 이름을 읽는다.
		 * 이 mapper는 SDK와 똑같이 변환해야 한다.
		 * 더 너그럽게 변환하면, 예를 들어 {@code "arguments":""}를 받아들이는 mapper는 SDK가 오류로 답할 요청을 숨긴 tool만 "모르는 tool"로 답해서
		 * 그런 tool이 있다는 사실을 알려 준다.
		 * 읽을 수 없으면 SDK가 같은 params로 오류를 내도록 넘긴다.
		 */
		private String toolName(Object params) {
			try {
				return jsonMapper.convertValue(params, new TypeRef<McpSchema.CallToolRequest>() {
				}).name();
			}
			catch (RuntimeException ex) {
				return null;
			}
		}

		@Override
		public Mono<Void> handleNotification(McpTransportContext context,
				McpSchema.JSONRPCNotification notification) {
			return this.sdk.handleNotification(context, notification);
		}
	}
}
