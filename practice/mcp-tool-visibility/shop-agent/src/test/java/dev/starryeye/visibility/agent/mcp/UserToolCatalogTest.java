package dev.starryeye.visibility.agent.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자마다 다른 tool 목록을 access token별로 cache하는지 본다(2026-07-28 Caching의 "private" 규칙).
 */
class UserToolCatalogTest {

	/** 테스트가 시간을 앞으로 돌릴 수 있는 시계다. */
	static final class 시계 extends Clock {

		private Instant now = Instant.parse("2026-10-05T00:00:00Z");

		void 지나감(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}
	}

	시계 clock = new 시계();

	/** 사용자 이름 → 지금 그 사용자의 access token이다. step-up은 값을 바꿔 흉내 낸다. */
	Map<String, String> tokens = new HashMap<>(Map.of("a", "token-a", "b", "token-b"));

	/** 목록을 받을 때마다 그때의 token을 남긴다. */
	List<String> loads = new ArrayList<>();

	String current;

	Runnable lastOnUnknownTool;

	UserToolCatalog catalog = new UserToolCatalog(onUnknownTool -> {
		this.loads.add(this.current);
		this.lastOnUnknownTool = onUnknownTool;
		return List.of(도구("tool-for-" + this.current));
	}, user -> {
		this.current = this.tokens.get(user.getName());
		return this.current;
	}, this.clock);

	static Authentication 사용자(String name) {
		return new TestingAuthenticationToken(name, null);
	}

	static ToolCallback 도구(String name) {
		ToolDefinition definition = DefaultToolDefinition.builder()
				.name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
		return new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String toolInput) {
				return name;
			}
		};
	}

	static List<String> 이름(List<ToolCallback> callbacks) {
		return callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList();
	}

	@Test
	void 같은_token이면_TTL_안에서는_다시_받지_않는다() {
		this.catalog.callbacks(사용자("a"));
		this.clock.지나감(Duration.ofMinutes(4).plusSeconds(59));
		this.catalog.callbacks(사용자("a"));

		assertThat(this.loads).containsExactly("token-a");
	}

	@Test
	void token이_다르면_목록을_따로_둔다() {
		assertThat(이름(this.catalog.callbacks(사용자("a")))).containsExactly("tool-for-token-a");
		assertThat(이름(this.catalog.callbacks(사용자("b")))).containsExactly("tool-for-token-b");
		assertThat(이름(this.catalog.callbacks(사용자("a")))).containsExactly("tool-for-token-a");
		assertThat(this.loads).containsExactly("token-a", "token-b");
	}

	@Test
	void TTL이_지나면_다시_받고_만료_항목은_지운다() {
		this.catalog.callbacks(사용자("a"));
		this.clock.지나감(UserToolCatalog.TTL);
		this.catalog.callbacks(사용자("b"));

		// a의 항목은 만료되어 b를 꺼낼 때 함께 지워졌다.
		assertThat(this.catalog.size()).isEqualTo(1);
		this.catalog.callbacks(사용자("a"));
		assertThat(this.loads).containsExactly("token-a", "token-b", "token-a");
	}

	@Test
	void step_up으로_token이_바뀌면_다시_받는다() {
		this.catalog.callbacks(사용자("a"));
		this.tokens.put("a", "token-a-after-step-up");
		this.catalog.callbacks(사용자("a"));

		assertThat(this.loads).containsExactly("token-a", "token-a-after-step-up");
	}

	@Test
	void 모르는_tool_오류가_나면_그_token의_목록을_버린다() {
		this.catalog.callbacks(사용자("a"));
		this.catalog.callbacks(사용자("b"));
		this.lastOnUnknownTool.run();

		this.catalog.callbacks(사용자("a"));
		this.catalog.callbacks(사용자("b"));
		assertThat(this.loads).containsExactly("token-a", "token-b", "token-b");
	}

	@Test
	void key는_token_값을_그대로_두지_않는다() {
		String key = UserToolCatalog.key("token-a");

		assertThat(key).hasSize(64).isNotEqualTo("token-a").isEqualTo(UserToolCatalog.key("token-a"));
		assertThat(UserToolCatalog.key("token-b")).isNotEqualTo(key);
	}
}
