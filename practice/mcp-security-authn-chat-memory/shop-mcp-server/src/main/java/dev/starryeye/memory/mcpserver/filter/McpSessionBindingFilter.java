package dev.starryeye.memory.mcpserver.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP session을 그 session을 연 사용자에게 묶는다(MCP Security Best Practices — Session Hijacking, SHOULD).
 *
 * <p>session ID는 인증을 대신하지 않는다. 모든 요청은 먼저 token으로 인증된다.
 * 이 filter는 그 뒤에 돌며, 요청의 {@code Mcp-Session-Id}가 다른 사용자(token의 {@code sub})에게 발급된 것이면
 * 403으로 막는다.
 *
 * <p>묶는 시점은 transport가 응답에 {@code Mcp-Session-Id} header를 쓰는 순간이다(응답 wrapper).
 * 본문이 나가기 전에 묶이므로, client가 다음 요청을 보낼 때는 이미 묶여 있다.
 * {@code DELETE}로 session이 끝나면 묶음을 지운다.
 *
 * <p>{@code DELETE} 없이 사라진 client의 묶음이 쌓이지 않도록, 묶음마다 마지막으로 쓴 시각을 둔다.
 * {@link #DEFAULT_IDLE_TIMEOUT} 동안 쓰지 않은 묶음은 지운다.
 * 요청을 처리할 때 {@link #SWEEP_INTERVAL}에 한 번 map 전체를 훑는다.
 * 묶음이 없는 session ID로 온 요청은 끝난 session으로 보고 {@code 404}로 답한다(MCP: 끝낸 session ID에는 404).
 * transport에 넘기면, 묶음만 지워지고 transport에 남은 session을 다른 사용자가 이어 쓸 수 있기 때문이다.
 * client는 {@code 404}를 받으면 새 {@code initialize}로 다시 연다.
 */
public class McpSessionBindingFilter extends OncePerRequestFilter {

	static final String SESSION_ID_HEADER = "Mcp-Session-Id";

	/** 이 시간 동안 쓰지 않은 묶음은 지운다. */
	static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofHours(1);

	/** map 전체 정리는 이 간격보다 자주 하지 않는다. */
	static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

	private final Map<String, Binding> bindings = new ConcurrentHashMap<>();

	private final Clock clock;

	private final Duration idleTimeout;

	private final AtomicReference<Instant> nextSweep;

	public McpSessionBindingFilter() {
		this(Clock.systemUTC(), DEFAULT_IDLE_TIMEOUT);
	}

	McpSessionBindingFilter(Clock clock, Duration idleTimeout) {
		this.clock = clock;
		this.idleTimeout = idleTimeout;
		this.nextSweep = new AtomicReference<>(clock.instant().plus(SWEEP_INTERVAL));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			chain.doFilter(request, response);
			return;
		}
		Instant now = this.clock.instant();
		sweepIfDue(now);
		String user = authentication.getName();
		String sessionId = request.getHeader(SESSION_ID_HEADER);
		if (sessionId != null) {
			Binding binding = this.bindings.get(sessionId);
			if (binding == null || binding.idleSince(now, this.idleTimeout)) {
				if (binding != null) {
					this.bindings.remove(sessionId, binding);
				}
				response.sendError(HttpServletResponse.SC_NOT_FOUND, "MCP session not found");
				return;
			}
			if (!binding.owner().equals(user)) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, "MCP session belongs to another user");
				return;
			}
			binding.touch(now);
		}

		chain.doFilter(request, new BindingResponse(response, user, now));

		if ("DELETE".equals(request.getMethod()) && sessionId != null && response.getStatus() < 300) {
			this.bindings.remove(sessionId);
		}
	}

	/** 지금 남아 있는 묶음 수다. 테스트가 정리를 확인할 때 쓴다. */
	int bindingCount() {
		return this.bindings.size();
	}

	private void sweepIfDue(Instant now) {
		Instant due = this.nextSweep.get();
		if (now.isBefore(due) || !this.nextSweep.compareAndSet(due, now.plus(SWEEP_INTERVAL))) {
			return;
		}
		this.bindings.values().removeIf(binding -> binding.idleSince(now, this.idleTimeout));
	}

	/** session을 연 사용자와, 그 session으로 마지막 요청이 온 시각이다. */
	private static final class Binding {

		private final String owner;

		private volatile Instant lastAccess;

		Binding(String owner, Instant lastAccess) {
			this.owner = owner;
			this.lastAccess = lastAccess;
		}

		String owner() {
			return this.owner;
		}

		void touch(Instant now) {
			this.lastAccess = now;
		}

		boolean idleSince(Instant now, Duration idleTimeout) {
			return this.lastAccess.plus(idleTimeout).isBefore(now);
		}
	}

	/** transport가 {@code Mcp-Session-Id}를 쓰는 순간 그 session을 현재 사용자에게 묶는다. */
	private final class BindingResponse extends HttpServletResponseWrapper {

		private final String user;

		private final Instant now;

		BindingResponse(HttpServletResponse response, String user, Instant now) {
			super(response);
			this.user = user;
			this.now = now;
		}

		@Override
		public void setHeader(String name, String value) {
			bind(name, value);
			super.setHeader(name, value);
		}

		@Override
		public void addHeader(String name, String value) {
			bind(name, value);
			super.addHeader(name, value);
		}

		private void bind(String name, String value) {
			if (SESSION_ID_HEADER.equalsIgnoreCase(name) && value != null) {
				bindings.putIfAbsent(value, new Binding(this.user, this.now));
			}
		}
	}
}
