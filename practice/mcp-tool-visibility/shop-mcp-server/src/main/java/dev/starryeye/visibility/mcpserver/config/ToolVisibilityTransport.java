package dev.starryeye.visibility.mcpserver.config;

import dev.starryeye.visibility.mcpserver.security.McpCaller;
import dev.starryeye.visibility.mcpserver.tool.ToolVisibility;

import io.modelcontextprotocol.common.McpTransportContext;
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
 */
public final class ToolVisibilityTransport implements McpStatelessServerTransport {

	private static final Logger log = LoggerFactory.getLogger(ToolVisibilityTransport.class);

	private final McpStatelessServerTransport delegate;

	private final ToolVisibility visibility;

	public ToolVisibilityTransport(McpStatelessServerTransport delegate, ToolVisibility visibility) {
		this.delegate = delegate;
		this.visibility = visibility;
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

		@Override
		public Mono<Void> handleNotification(McpTransportContext context,
				McpSchema.JSONRPCNotification notification) {
			return this.sdk.handleNotification(context, notification);
		}
	}
}
