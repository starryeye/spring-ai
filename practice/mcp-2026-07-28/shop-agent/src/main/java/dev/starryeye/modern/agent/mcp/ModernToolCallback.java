package dev.starryeye.modern.agent.mcp;

import dev.starryeye.modern.agent.security.StepUpRequiredException;

import io.modelcontextprotocol.modern.McpSchema;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * MCP Server의 tool 하나를 Spring AI tool로 보이게 한다.
 *
 * <p>모델이 tool을 고르면 Spring AI는 {@link #call}을 부르고, 돌려준 문자열을 tool 결과로 모델에게 준다.
 * 이 class는 그 호출을 {@link ModernMcpClient#callTool}로 넘기고, 결과 content의 text를 이어 붙여 돌려준다.
 * tool이 실패한 결과({@code isError})도 같은 방식으로 돌려준다.
 * 모델이 그 문장을 보고 사용자에게 설명할 수 있기 때문이다.
 *
 * <p>예외는 둘로 나눈다.
 * {@link StepUpRequiredException}은 그대로 던진다.
 * Spring AI는 {@link ToolExecutionException}만 잡으므로, 이 예외는 tool loop를 지나 채팅 응답까지 올라가 consent 카드가 된다.
 * 모델에게 오류 문장으로 넘기면 모델은 "권한이 없습니다" 같은 답을 지어낼 뿐, 사용자는 권한을 줄 기회를 얻지 못한다.
 * MCP Server의 다른 오류({@link ModernMcpException})는 {@link ToolExecutionException}으로 바꾼다.
 * 그러면 Spring AI 기본 처리가 오류 문장을 모델에게 tool 결과로 주고 turn이 이어진다.
 * {@code spring.ai.tools.throw-exception-on-error}를 켜면 기본 처리가 대신 다시 던진다.
 * 모르는 tool 오류면 그 전에 {@code onUnknownTool}을 불러 낡은 목록을 버린다.
 */
public class ModernToolCallback implements ToolCallback {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final McpSchema.Tool tool;

	private final ModernMcpClient client;

	private final Supplier<String> accessToken;

	private final Runnable onUnknownTool;

	private final ToolDefinition definition;

	/**
	 * @param accessToken 부를 때마다 지금의 access token을 준다. 목록을 받은 뒤 refresh된 token도 그대로 쓴다
	 * @param onUnknownTool MCP Server가 이 tool을 모른다고 답하면 부른다
	 */
	public ModernToolCallback(McpSchema.Tool tool, ModernMcpClient client, Supplier<String> accessToken,
			Runnable onUnknownTool) {
		this.tool = tool;
		this.client = client;
		this.accessToken = accessToken;
		this.onUnknownTool = onUnknownTool;
		this.definition = DefaultToolDefinition.builder()
				.name(tool.name())
				.description(description(tool))
				.inputSchema(JSON.writeValueAsString(tool.inputSchema()))
				.build();
	}

	/** Spring AI의 {@link ToolDefinition}은 빈 설명을 받지 않으므로 title이나 이름으로 채운다. */
	private static String description(McpSchema.Tool tool) {
		if (StringUtils.hasText(tool.description())) {
			return tool.description();
		}
		return StringUtils.hasText(tool.title()) ? tool.title() : tool.name();
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.definition;
	}

	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, @Nullable ToolContext toolContext) {
		Map<String, Object> arguments = arguments(toolInput);
		try {
			McpSchema.CallToolResult result = this.client.callTool(this.accessToken.get(), this.tool.name(), arguments);
			return text(result);
		}
		catch (UnknownToolException ex) {
			this.onUnknownTool.run();
			throw new ToolExecutionException(this.definition, ex);
		}
		catch (ModernMcpException ex) {
			throw new ToolExecutionException(this.definition, ex);
		}
	}

	/** 모델이 만든 tool 입력(JSON object)이다. 비어 있으면 인자 없이 부른다. */
	private Map<String, Object> arguments(String toolInput) {
		if (!StringUtils.hasText(toolInput)) {
			return Map.of();
		}
		try {
			JsonNode node = JSON.readTree(toolInput);
			if (!node.isObject()) {
				throw new ToolExecutionException(this.definition,
						new IllegalArgumentException("tool 입력은 JSON object여야 한다: " + toolInput));
			}
			return JSON.convertValue(node, JSON.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
		}
		catch (JacksonException ex) {
			throw new ToolExecutionException(this.definition,
					new IllegalArgumentException("tool 입력을 JSON으로 읽지 못했다: " + ex.getOriginalMessage()));
		}
	}

	/** text가 아닌 content(그림, resource 등)는 모델에게 문자열로 줄 수 없어 뺀다. */
	private static String text(McpSchema.CallToolResult result) {
		return result.content().stream()
				.filter(McpSchema.TextContent.class::isInstance)
				.map(content -> ((McpSchema.TextContent) content).text())
				.collect(Collectors.joining("\n"));
	}
}
