package dev.starryeye.modern.mcpserver.filter;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallRateLimiterTest {

	/** 테스트가 시간을 앞으로 돌릴 수 있는 시계다. */
	static final class 시계 extends Clock {

		private Instant now = Instant.parse("2026-10-01T00:00:00Z");

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

	ToolCallRateLimiter limiter = new ToolCallRateLimiter(3, Duration.ofMinutes(1), this.clock);

	@Test
	void 상한까지는_받고_넘으면_가장_오래된_호출이_1분을_벗어날_때까지_기다리게_한다() {
		assertThat(this.limiter.tryAcquire("alice")).isEmpty();
		this.clock.지나감(Duration.ofSeconds(10));
		assertThat(this.limiter.tryAcquire("alice")).isEmpty();
		this.clock.지나감(Duration.ofSeconds(10));
		assertThat(this.limiter.tryAcquire("alice")).isEmpty();
		this.clock.지나감(Duration.ofSeconds(10));

		assertThat(this.limiter.tryAcquire("alice")).contains(Duration.ofSeconds(30));
	}

	@Test
	void 가장_오래된_호출이_1분을_벗어나면_다시_받는다() {
		this.limiter.tryAcquire("alice");
		this.clock.지나감(Duration.ofSeconds(10));
		this.limiter.tryAcquire("alice");
		this.limiter.tryAcquire("alice");

		this.clock.지나감(Duration.ofSeconds(50));

		assertThat(this.limiter.tryAcquire("alice")).isEmpty();
		assertThat(this.limiter.tryAcquire("alice")).contains(Duration.ofSeconds(10));
	}

	@Test
	void 거절한_호출은_세지_않는다() {
		this.limiter.tryAcquire("alice");
		this.limiter.tryAcquire("alice");
		this.limiter.tryAcquire("alice");
		for (int i = 0; i < 5; i++) {
			assertThat(this.limiter.tryAcquire("alice")).isPresent();
		}

		this.clock.지나감(Duration.ofMinutes(1));

		assertThat(this.limiter.tryAcquire("alice")).isEmpty();
	}

	@Test
	void 사용자마다_따로_센다() {
		this.limiter.tryAcquire("alice");
		this.limiter.tryAcquire("alice");
		this.limiter.tryAcquire("alice");

		assertThat(this.limiter.tryAcquire("alice")).isPresent();
		assertThat(this.limiter.tryAcquire("bob")).isEmpty();
	}

	@Test
	void 한동안_부르지_않은_사용자의_기록은_지운다() {
		this.limiter.tryAcquire("alice");
		this.limiter.tryAcquire("bob");
		assertThat(this.limiter.trackedSubjects()).isEqualTo(2);

		this.clock.지나감(Duration.ofMinutes(2));
		this.limiter.tryAcquire("carol");

		assertThat(this.limiter.trackedSubjects()).isEqualTo(1);
	}
}
