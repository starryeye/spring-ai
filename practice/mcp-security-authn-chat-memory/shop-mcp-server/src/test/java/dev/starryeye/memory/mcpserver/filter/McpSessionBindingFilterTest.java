package dev.starryeye.memory.mcpserver.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 묶음의 만료와 정리를 시계를 움직여 확인한다. 실제 transport와 함께 도는 경우는 {@code McpAuthorizationStandardTest}가 본다. */
class McpSessionBindingFilterTest {

	static final Duration IDLE = Duration.ofHours(1);

	MutableClock clock = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));

	McpSessionBindingFilter filter = new McpSessionBindingFilter(this.clock, IDLE);

	/** filter 뒤의 transport가 불린 횟수다. */
	AtomicInteger transportCalls = new AtomicInteger();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	/** transport처럼 initialize 응답에 session ID를 쓴다. */
	void session_을_연다(String user, String sessionId) throws Exception {
		MockHttpServletResponse response = send(user, "POST", null,
				(request, res) -> ((HttpServletResponse) res).setHeader(McpSessionBindingFilter.SESSION_ID_HEADER, sessionId));
		assertThat(response.getStatus()).isEqualTo(200);
	}

	MockHttpServletResponse 요청(String user, String sessionId) throws Exception {
		return send(user, "POST", sessionId, (request, response) -> {
		});
	}

	MockHttpServletResponse send(String user, String method, String sessionId, FilterChain transport) throws Exception {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null, "ROLE_USER"));
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/mcp");
		if (sessionId != null) {
			request.addHeader(McpSessionBindingFilter.SESSION_ID_HEADER, sessionId);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.filter.doFilter(request, response, (req, res) -> {
			this.transportCalls.incrementAndGet();
			transport.doFilter(req, res);
		});
		return response;
	}

	@Test
	void 쓰는_동안에는_한_시간이_넘게_지나도_묶음이_남는다() throws Exception {
		session_을_연다("alice", "s1");

		this.clock.advance(Duration.ofMinutes(50));
		assertThat(요청("alice", "s1").getStatus()).isEqualTo(200);
		this.clock.advance(Duration.ofMinutes(50));
		assertThat(요청("alice", "s1").getStatus()).isEqualTo(200);
		assertThat(요청("bob", "s1").getStatus()).isEqualTo(403);
	}

	@Test
	void 한_시간_쓰지_않은_session_은_묶음이_지워지고_404다() throws Exception {
		session_을_연다("alice", "s1");
		int before = this.transportCalls.get();

		this.clock.advance(IDLE.plusSeconds(1));

		assertThat(요청("alice", "s1").getStatus()).isEqualTo(404);
		assertThat(this.transportCalls).hasValue(before);
		assertThat(this.filter.bindingCount()).isZero();
	}

	@Test
	void 묶음이_지워진_session_을_다른_사용자도_쓸_수_없다() throws Exception {
		// 묶음만 지우고 요청을 transport에 넘기면, transport에 남은 session을 다른 사용자가 이어 쓸 수 있다.
		session_을_연다("alice", "s1");
		int before = this.transportCalls.get();

		this.clock.advance(IDLE.plusSeconds(1));

		assertThat(요청("bob", "s1").getStatus()).isEqualTo(404);
		assertThat(this.transportCalls).hasValue(before);
	}

	@Test
	void 요청을_처리할_때_오래된_다른_묶음도_정리한다() throws Exception {
		session_을_연다("alice", "s1");
		session_을_연다("bob", "s2");
		assertThat(this.filter.bindingCount()).isEqualTo(2);

		this.clock.advance(IDLE.plusSeconds(1));
		session_을_연다("carol", "s3");

		assertThat(this.filter.bindingCount()).isEqualTo(1);
		assertThat(요청("carol", "s3").getStatus()).isEqualTo(200);
	}

	@Test
	void 성공한_DELETE_는_묶음을_바로_지운다() throws Exception {
		session_을_연다("alice", "s1");

		send("alice", "DELETE", "s1", (request, response) -> {
		});

		assertThat(this.filter.bindingCount()).isZero();
		assertThat(요청("alice", "s1").getStatus()).isEqualTo(404);
	}

	static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
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
}
