package dev.starryeye.modern.agent.mcp;

import io.modelcontextprotocol.modern.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 사용자마다 다른 MCP tool 목록을, MCP Server가 정한 기간과 범위대로 들고 있다(안내서 12장).
 *
 * <p>MCP Server는 사용자 역할로 목록을 거른다.
 * 그래서 목록을 언제까지, 누구와 같이 써도 되는지는 MCP Server가 {@code tools/list} 결과에 적어 준다.
 * {@code ttlMs}는 cache해도 되는 기간이다.
 * 없거나 0이면 cache하지 않고 질문마다 다시 받는다.
 * {@code cacheScope}가 {@code private}이면 그 access token에서만 쓴다.
 * 다른 사용자의 목록이 섞이지 않도록 token마다 따로 둔다.
 * {@code public}이면 사용자와 상관없는 목록이라 하나만 둔다.
 * step-up으로 token이 바뀌면 key도 바뀌므로 목록을 다시 받는다.
 *
 * <p>목록은 tool 정의만 cache하고, callback은 질문마다 그 사용자로 새로 만든다.
 * {@code public} 목록을 같이 써도 tool은 부르는 사용자의 token으로 불러야 하기 때문이다.
 * tool이 "모르는 tool" 오류를 받으면 목록이 낡은 것이므로 그 목록을 버린다({@link #forget}).
 *
 * <p>처음 쓸 때는 {@code server/discover}로 MCP Server가 2026-07-28을 지원하는지 한 번 확인한다.
 * 지원하지 않으면 예외로 멈추고, 다음 질문 때 다시 확인한다.
 */
public class UserToolCatalog {

	private static final Logger log = LoggerFactory.getLogger(UserToolCatalog.class);

	/**
	 * {@code public} 목록의 key다.
	 * token의 key는 64자리 hex라서 겹치지 않는다.
	 */
	static final String PUBLIC = "public";

	private record Entry(List<McpSchema.Tool> tools, Instant expiresAt) {
	}

	/**
	 * 쪽을 모두 합친 목록이다.
	 * 여러 쪽이면 가장 짧은 {@code ttlMs}와 가장 좁은 {@code cacheScope}를 따른다.
	 */
	private record Listing(List<McpSchema.Tool> tools, long ttlMs, boolean shared) {
	}

	private final ModernMcpClient client;

	private final Function<Authentication, String> accessToken;

	private final Clock clock;

	private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

	private volatile boolean discovered;

	/**
	 * @param accessToken 사용자의 지금 access token을 꺼낸다. 만료된 token이면 refresh한 값을 준다
	 */
	public UserToolCatalog(ModernMcpClient client, Function<Authentication, String> accessToken, Clock clock) {
		this.client = client;
		this.accessToken = accessToken;
		this.clock = clock;
	}

	/**
	 * 이 사용자의 tool 목록이다.
	 * callback은 {@code user}를 묶어 두고, 부를 때마다 그 사용자의 지금 token을 꺼낸다.
	 * 그래서 tool이 요청 thread 밖에서 불려도 사용자를 잃지 않는다.
	 */
	public List<ToolCallback> callbacks(Authentication user) {
		String token = this.accessToken.apply(user);
		discoverOnce(token);
		Instant now = this.clock.instant();
		this.entries.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
		List<McpSchema.Tool> tools = cached(token);
		if (tools != null) {
			log.info("tool 목록을 cache에서 꺼낸다 (사용자={}, {}개)", user.getName(), tools.size());
		}
		else {
			tools = load(token, now);
			log.info("tool 목록을 새로 받았다 (사용자={}, {}개)", user.getName(), tools.size());
		}
		return tools.stream()
				.<ToolCallback>map(tool -> new ModernToolCallback(tool, this.client, () -> this.accessToken.apply(user),
						() -> forget(token)))
				.toList();
	}

	/**
	 * 이 token으로 받은 목록을 버린다.
	 * 다음 질문 때 다시 받는다.
	 * 이 token이 같이 쓰던 {@code public} 목록도 낡았을 수 있으므로 함께 버린다.
	 */
	public void forget(String accessToken) {
		boolean removed = this.entries.remove(key(accessToken)) != null;
		removed |= this.entries.remove(PUBLIC) != null;
		if (removed) {
			log.info("모르는 tool 오류로 이 token의 tool 목록을 버린다");
		}
	}

	private void discoverOnce(String token) {
		if (this.discovered) {
			return;
		}
		McpSchema.DiscoverResult result = this.client.discover(token);
		this.discovered = true;
		log.info("MCP Server가 {}을 지원한다 (supportedVersions={})", ModernMcpClient.PROTOCOL_VERSION,
				(result != null) ? result.supportedVersions() : "?");
	}

	private List<McpSchema.Tool> cached(String token) {
		Entry entry = this.entries.get(key(token));
		if (entry == null) {
			entry = this.entries.get(PUBLIC);
		}
		return (entry != null) ? entry.tools() : null;
	}

	private List<McpSchema.Tool> load(String token, Instant now) {
		Listing listing = listAll(token);
		if (listing.ttlMs() > 0) {
			this.entries.put(listing.shared() ? PUBLIC : key(token),
					new Entry(listing.tools(), now.plusMillis(listing.ttlMs())));
		}
		return listing.tools();
	}

	/**
	 * {@code nextCursor}가 없을 때까지 따라가 합친다.
	 * 같은 cursor가 다시 오면 끝나지 않으므로 멈춘다.
	 */
	private Listing listAll(String token) {
		List<McpSchema.Tool> tools = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		long ttlMs = Long.MAX_VALUE;
		boolean shared = true;
		String cursor = null;
		do {
			McpSchema.ListToolsResult page = this.client.listTools(token, cursor);
			tools.addAll(page.tools());
			ttlMs = Math.min(ttlMs, page.ttlMs());
			shared &= page.cacheScope() == McpSchema.CacheScope.PUBLIC;
			cursor = page.nextCursor();
			if (cursor != null && !seen.add(cursor)) {
				throw new IllegalStateException("MCP Server가 같은 nextCursor를 다시 주었다: " + cursor);
			}
		}
		while (cursor != null);
		return new Listing(List.copyOf(tools), ttlMs, shared);
	}

	int size() {
		return this.entries.size();
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
