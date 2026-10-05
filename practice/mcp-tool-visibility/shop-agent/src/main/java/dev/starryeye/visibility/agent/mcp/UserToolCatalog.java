package dev.starryeye.visibility.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 사용자마다 다른 MCP tool 목록을 access token별로 들고 있다(안내서 12장).
 *
 * <p>MCP Server는 사용자 역할로 목록을 거른다.
 * 그래서 모든 사용자가 같이 쓰는 목록 하나를 cache하면, 먼저 받은 사람의 목록이 다른 사람에게 간다.
 * MCP 2026-07-28 Caching은 사용자마다 다른 결과를 {@code "private"}로 보고, 다른 access token과 cache를 나누지 못하게 한다.
 * 이 class는 그 규칙을 지금 버전(2025-11-25)에서 미리 따른다.
 *
 * <p>2025-11-25 서버는 목록의 유효 시간({@code ttlMs})을 알려 주지 않으므로 client가 정한다.
 * TTL 안이면 다시 받지 않고, 지나면 다음 질문 때 다시 받는다.
 * 주기적으로 미리 받지는 않는다.
 */
public class UserToolCatalog {

	private static final Logger log = LoggerFactory.getLogger(UserToolCatalog.class);

	/** 2026-07-28 Caching의 예시 값과 같다. 2026-07-28로 올리면 서버의 {@code ttlMs}를 쓴다. */
	public static final Duration TTL = Duration.ofMinutes(5);

	/** 지금 thread의 사용자 token으로 {@code tools/list}를 받아 callback으로 만든다. */
	@FunctionalInterface
	public interface ToolLoader {

		/** {@code onUnknownTool}은 이 목록의 tool이 "모르는 tool" 오류로 끝날 때 부른다. */
		List<ToolCallback> load(Runnable onUnknownTool);
	}

	private record Entry(List<ToolCallback> callbacks, Instant expiresAt) {
	}

	private final ToolLoader loader;

	private final Function<Authentication, String> accessToken;

	private final Clock clock;

	private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

	public UserToolCatalog(ToolLoader loader, Function<Authentication, String> accessToken, Clock clock) {
		this.loader = loader;
		this.accessToken = accessToken;
		this.clock = clock;
	}

	/**
	 * MCP client로 목록을 받는다.
	 * 모델에게 보이는 이름이 서버가 준 이름과 같도록 {@code prefixedToolName}을 그대로 넣는다.
	 */
	public static ToolLoader mcp(McpSyncClient client) {
		return onUnknownTool -> client.listTools().tools().stream()
				.<ToolCallback>map(tool -> new UnknownToolAwareToolCallback(SyncMcpToolCallback.builder()
						.mcpClient(client)
						.tool(tool)
						.prefixedToolName(tool.name())
						.build(), onUnknownTool))
				.toList();
	}

	/**
	 * 이 사용자의 tool 목록이다.
	 * 요청 thread에서 부른다. 목록을 새로 받을 때 그 thread의 SecurityContext로 사용자 token이 붙으므로, 호출자는 현재 thread의 Authentication을 넘겨야 한다.
	 */
	public List<ToolCallback> callbacks(Authentication user) {
		String key = key(this.accessToken.apply(user));
		Instant now = this.clock.instant();
		this.entries.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
		Entry cached = this.entries.get(key);
		if (cached != null) {
			log.info("tool 목록을 cache에서 꺼낸다 (사용자={}, {}개)", user.getName(), cached.callbacks().size());
			return cached.callbacks();
		}
		List<ToolCallback> callbacks = this.loader.load(() -> invalidate(key));
		this.entries.put(key, new Entry(callbacks, now.plus(TTL)));
		log.info("tool 목록을 새로 받았다 (사용자={}, {}개)", user.getName(), callbacks.size());
		return callbacks;
	}

	int size() {
		return this.entries.size();
	}

	void invalidate(String key) {
		if (this.entries.remove(key) != null) {
			log.info("모르는 tool 오류로 이 token의 tool 목록을 버린다");
		}
	}

	/** 로그나 heap dump에서 token이 그대로 보이지 않게 하려고 token 값을 그대로 key로 두지 않는다. */
	static String key(String accessToken) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(accessToken.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
