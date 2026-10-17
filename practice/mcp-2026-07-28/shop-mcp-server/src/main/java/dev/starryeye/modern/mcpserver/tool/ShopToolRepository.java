package dev.starryeye.modern.mcpserver.tool;

import dev.starryeye.modern.mcpserver.security.McpCaller;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK의 2026-07-28 서버가 {@code tools/list}·{@code tools/call}을 처리할 때 tool을 받아 가는 곳이다.
 *
 * <p>세 메서드 모두 요청마다 {@link McpRequestContext}를 받는다.
 * 그 안의 transport context에 검증한 token의 사용자와 {@code Mcp-Name}이 있다({@link McpCaller}).
 * 그래서 사용자마다 다른 목록을 주고, 숨긴 tool을 정말 없는 tool처럼 다룰 수 있다(안내서 12장).
 *
 * <ul>
 *   <li>{@code list}는 역할로 받을 수 없는 scope의 tool을 빼고, cache 기간({@code ttlMs})과 공유 범위({@code cacheScope})를 단다.</li>
 *   <li>{@code find}는 숨긴 tool에 {@code null}을 준다. 그러면 SDK가 없는 tool과 같은 {@code Unknown tool} 오류로 답한다.</li>
 *   <li>{@code call}은 실행할 이름이 {@code Mcp-Name} header와 같을 때만 handler를 부른다.</li>
 * </ul>
 */
public class ShopToolRepository implements McpSyncToolRepository {

	private static final Logger log = LoggerFactory.getLogger(ShopToolRepository.class);

	private final Map<String, ShopTool> tools = new LinkedHashMap<>();

	private final ToolVisibility visibility;

	private final Duration listTtl;

	public ShopToolRepository(List<ShopTool> tools, ToolVisibility visibility, Duration listTtl) {
		tools.forEach(tool -> this.tools.put(tool.name(), tool));
		this.visibility = visibility;
		this.listTtl = listTtl;
	}

	/**
	 * 이 사용자에게 보이는 tool이다. 순서는 정의한 순서 그대로다.
	 *
	 * <p>목록은 역할마다 달라서 다른 access token과 cache를 나누면 안 된다. 그래서 {@code cacheScope}는 {@code private}이다.
	 * 목록 하나로 끝나므로 {@code nextCursor}는 없다.
	 */
	@Override
	public ToolsPage list(McpRequestContext ctx, String cursor) {
		String subject = McpCaller.subject(ctx.transportContext()).orElse(null);
		List<Tool> visible = this.tools.values().stream()
				.filter(tool -> !this.visibility.hidden(subject, tool.name()))
				.map(ShopTool::tool)
				.toList();
		log.info("tools/list — 사용자={}, 역할={}, 보인 tool={}/{}", subject, this.visibility.roleOf(subject),
				visible.size(), this.tools.size());
		return new ToolsPage(visible, null, this.listTtl.toMillis(), CacheScope.PRIVATE);
	}

	/**
	 * 이 사용자에게 보이는 tool이면 그 정의를, 아니면 {@code null}을 준다.
	 * 숨긴 tool은 scope를 늘려도 쓸 수 없으므로 step-up을 시작하지 않고, 그런 tool이 있다는 사실도 알리지 않는다.
	 * SDK는 입력 검증보다 먼저 이 메서드를 부른다. 그래서 인자가 틀린 호출에도 숨긴 tool과 없는 tool이 같은 답을 받는다.
	 */
	@Override
	public Tool find(McpRequestContext ctx, String name) {
		ShopTool tool = visibleTool(ctx, name);
		return (tool != null) ? tool.tool() : null;
	}

	/**
	 * scope 검사는 {@code Mcp-Name} header의 이름으로 하므로, 실행하는 tool도 그 이름이어야 한다.
	 *
	 * <p>SDK transport는 본문의 이름이 header와 다르면 {@code 400}으로 막는다.
	 * 하지만 SDK 2.2.0-M1은 본문 이름이 문자열이 아니면 그 대조를 건너뛰고, Jackson이 숫자 {@code 1}을 {@code "1"}로 바꿔 실행한다.
	 * 이때 SDK가 본문에서 그대로 꺼낸 이름({@code primitiveName})은 비어 있다.
	 * 그래서 {@code primitiveName}이 있고 header·실행할 이름과 모두 같을 때만 실행한다.
	 * 아니면 없는 tool과 같은 오류로 끝낸다.
	 */
	@Override
	public McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
		String checked = McpCaller.mcpName(ctx.transportContext());
		String primitiveName = ctx.primitiveName();
		if (primitiveName == null || !primitiveName.equals(checked) || !primitiveName.equals(request.name())) {
			log.warn("실행할 tool 이름이 Mcp-Name과 다르다 — Mcp-Name={}, 본문 이름={}, 요청 이름={}", checked, primitiveName,
					request.name());
			throw unknownTool(request.name());
		}
		ShopTool tool = visibleTool(ctx, request.name());
		if (tool == null) {
			throw unknownTool(request.name());
		}
		McpCaller caller = McpCaller.from(ctx.transportContext());
		Map<String, Object> arguments = (request.arguments() != null) ? request.arguments() : Map.of();
		return McpSyncResponse.result(tool.handler().apply(caller, arguments));
	}

	private ShopTool visibleTool(McpRequestContext ctx, String name) {
		ShopTool tool = this.tools.get(name);
		if (tool == null) {
			return null;
		}
		String subject = McpCaller.subject(ctx.transportContext()).orElse(null);
		if (this.visibility.hidden(subject, name)) {
			log.info("숨긴 tool 호출 — 사용자={}, 역할={}, tool={}", subject, this.visibility.roleOf(subject), name);
			return null;
		}
		return tool;
	}

	/** SDK가 없는 tool에 주는 것과 같은 오류다. */
	private static McpException unknownTool(String name) {
		return McpException.invalidParams("Unknown tool: " + name);
	}
}
