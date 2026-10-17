package dev.starryeye.modern.mcpserver.tool;

import dev.starryeye.modern.mcpserver.security.McpCaller;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShopToolRepositoryTest {

	static final Map<String, Object> EMPTY_SCHEMA = Map.of("type", "object", "properties", Map.of());

	List<String> 실행한_tool = new CopyOnWriteArrayList<>();

	/** {@code "1"}은 숫자 이름 우회를 재현하려고 둔 쓰기 tool이다. 이 practice의 tool에는 이런 이름이 없다. */
	List<ShopTool> tools = List.of(
			시험_tool("searchProducts", "products:read"),
			시험_tool("updateStock", "products:write"),
			시험_tool("1", "products:write"));

	ShopToolRepository repository = new ShopToolRepository(this.tools,
			new ToolVisibility(ToolScopeRegistry.of(this.tools), Map.of("user", ToolVisibility.Role.STAFF)),
			Duration.ofMinutes(1));

	ShopTool 시험_tool(String name, String scope) {
		return new ShopTool(Tool.builder(name, EMPTY_SCHEMA).build(), scope, (caller, arguments) -> {
			this.실행한_tool.add(name);
			return CallToolResult.builder().addContent(TextContent.builder("실행함").build()).build();
		});
	}

	/** transport가 요청마다 만드는 context다. {@code mcpName}이 {@code null}이면 {@code Mcp-Name} header가 없던 요청이다. */
	static McpTransportContext 요청한_사용자(String subject, String mcpName) {
		Map<String, Object> values = new HashMap<>();
		values.put(McpCaller.SUBJECT, subject);
		values.put(McpCaller.CLIENT_ID, "test-client");
		if (mcpName != null) {
			values.put(McpCaller.MCP_NAME, mcpName);
		}
		return McpTransportContext.create(values);
	}

	/**
	 * 본문 {@code params.name}이 {@code name}인 {@code tools/call}에 대해 SDK가 만드는 {@link McpRequestContext}를 받는다.
	 *
	 * <p>이 context의 생성자는 SDK 밖에서 부를 수 없다.
	 * 그래서 요청 하나를 {@link McpServer}에 넣고, 그 server가 repository의 {@code find}에 넘기는 값을 잡는다.
	 * {@code primitiveName}을 SDK가 본문에서 꺼내는 방식 그대로 시험할 수 있다.
	 */
	static McpRequestContext context(McpTransportContext transport, Object name) {
		AtomicReference<McpRequestContext> seen = new AtomicReference<>();
		McpSyncToolRepository capturing = new McpSyncToolRepository() {

			@Override
			public ToolsPage list(McpRequestContext ctx, String cursor) {
				return ToolsPage.of(List.of());
			}

			@Override
			public Tool find(McpRequestContext ctx, String toolName) {
				seen.set(ctx);
				return null;
			}

			@Override
			public McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
				throw new AssertionError("find가 null을 주면 call은 불리지 않는다");
			}
		};
		McpServer server = McpServer.builder()
				.serverInfo(Implementation.builder("test", "0.0.1").build())
				.feature(ToolsFeature.ofSync(capturing))
				.build();
		Map<String, Object> params = new HashMap<>();
		params.put("name", name);
		params.put("_meta", Map.of(MetaKeys.PROTOCOL_VERSION, "2026-07-28", MetaKeys.CLIENT_CAPABILITIES, Map.of()));
		server.handleBlocking(transport, new JSONRPCRequest("tools/call", 1, params)).block();
		return seen.get();
	}

	/**
	 * SDK 2.2.0-M1은 본문 이름이 문자열이 아니면 {@code Mcp-Name}과 맞춰 보지 않는다.
	 * Jackson이 숫자 1을 {@code "1"}로 바꿔 실행하므로, header로 scope를 본 tool과 다른 tool이 실행될 수 있다.
	 * repository는 SDK가 본문에서 그대로 꺼낸 이름({@code primitiveName})이 {@code Mcp-Name}과 같을 때만 실행한다.
	 */
	@Test
	void call은_primitiveName이_Mcp_Name과_다르면_실행하지_않는다() {
		CallToolRequest request = CallToolRequest.builder("1").build();
		List<McpRequestContext> rejected = List.of(
				// 본문 name이 숫자라 primitiveName이 없다.
				context(요청한_사용자("user", "1"), 1),
				// header가 다른 tool을 가리킨다. transport가 먼저 막지만 여기서도 막는다.
				context(요청한_사용자("user", "searchProducts"), "1"),
				// Mcp-Name header가 없었다.
				context(요청한_사용자("user", null), "1"));

		for (McpRequestContext ctx : rejected) {
			assertThatThrownBy(() -> this.repository.call(ctx, request))
					.isInstanceOfSatisfying(McpException.class, ex -> {
						assertThat(ex.error().code()).isEqualTo(-32602);
						assertThat(ex.error().message()).isEqualTo("Unknown tool: 1");
					});
		}
		assertThat(this.실행한_tool).isEmpty();

		this.repository.call(context(요청한_사용자("user", "1"), "1"), request);

		assertThat(this.실행한_tool).containsExactly("1");
	}

	@Test
	void find는_숨긴_tool에_null을_준다() {
		McpRequestContext 손님 = context(요청한_사용자("user2", "updateStock"), "updateStock");
		McpRequestContext 점원 = context(요청한_사용자("user", "updateStock"), "updateStock");

		assertThat(this.repository.find(손님, "updateStock")).isNull();
		assertThat(this.repository.find(손님, "searchProducts")).isNotNull();
		assertThat(this.repository.find(점원, "updateStock")).isNotNull();
		assertThat(this.repository.find(점원, "nope")).isNull();
	}
}
