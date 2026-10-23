package dev.starryeye.cimd.mcpserver.config;

import dev.starryeye.cimd.mcpserver.security.McpCaller;
import dev.starryeye.cimd.mcpserver.tool.ToolVisibility;

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
 * SDK server와 WebMvc transport 사이에 끼어, 숨긴 tool의 {@code tools/call}에 "모르는 tool"로 답한다(안내서 12장).
 *
 * <p>{@code tools/list}를 거르는 일은 SDK의 tool filter가 한다({@link McpTransportConfig#toolVisibilityListFilter}).
 * 그 filter는 목록에서만 tool을 뺀다.
 * 숨긴 tool도 이름으로 부르면 SDK가 찾아서 실행하고, 그 전에 입력 검증을 먼저 돌려 그런 tool이 있다는 사실을 알린다.
 * 그래서 호출은 SDK가 tool을 찾기 전에 여기서 막는다.
 * 이 호출 guard는 {@code ToolScopeFilter}가 숨긴 tool의 scope 검사를 건너뛰는 것과 한 쌍이다.
 * guard가 없으면 scope를 보지 않은 호출이 그대로 실행되어, 조회 scope만 가진 손님 token으로도 재고가 바뀐다.
 *
 * <p>{@code WebMvcStatelessServerTransport}는 {@code final}이라 상속할 수 없어서 감싼다.
 * server가 생성될 때 넘기는 handler를 감싸, SDK가 tool을 찾기 전에 답한다.
 * 답은 SDK가 정말 없는 tool에 주는 오류와 같아서, 호출한 쪽은 숨긴 tool과 없는 tool을 구별할 수 없다.
 *
 * <p>{@code tools/list}의 응답은 바꾸지 않고, SDK가 거른 결과의 수만 한 요청에 한 줄 로그로 남긴다.
 * filter는 tool마다 불려서 그 한 줄을 남길 수 없다.
 */
public final class ToolVisibilityTransport implements McpStatelessServerTransport {

	private static final Logger log = LoggerFactory.getLogger(ToolVisibilityTransport.class);

	/**
	 * SDK 2.1.0이 없는 tool에 주는 message다(이름은 넣지 않는 고정 문자열이다).
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
					// 이 역할이 받을 수 없는 scope의 tool이라 step-up하지 않는다.
					// 그런 tool이 있다는 사실도 알리지 않는다.
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
			return response.doOnNext(r -> {
				if (r.result() instanceof McpSchema.ListToolsResult list) {
					log.info("tools/list — 사용자={}, 역할={}, 보인 tool={}/{}", subject, visibility.roleOf(subject),
							list.tools().size(), visibility.toolCount());
				}
			});
		}

		/**
		 * SDK server가 쓰는 mapper로 params에서 이름을 읽는다.
		 * 더 너그러운 mapper를 쓰면, 예를 들어 {@code "arguments":""}를 받아들이면, SDK가 오류로 답할 요청에 숨긴 tool만 "모르는 tool"로 답하게 된다.
		 * 그러면 그런 tool이 있다는 사실이 알려진다.
		 * 그래서 이 mapper는 SDK와 똑같이 변환해야 한다.
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
