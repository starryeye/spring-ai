package dev.starryeye.stateless.mcpserver.filter;

import org.springframework.util.Assert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 사용자(token의 {@code sub})마다 {@code tools/call} 횟수를 제한한다.
 * MCP 2025-11-25 Tools — Security Considerations는 서버가 tool 호출 속도를 제한하라고 한다("Servers MUST: ... Rate limit tool invocations").
 *
 * <p>사용자마다 최근 {@code window} 동안 받아들인 호출 시각을 기억한다.
 * window 안의 호출이 상한에 닿으면 거절하고, 가장 오래된 호출이 window를 벗어날 때까지 기다릴 시간을 알려 준다.
 * 거절한 호출은 세지 않는다.
 *
 * <p>한동안 부르지 않은 사용자의 기록은 지운다.
 * window마다 한 번, 마지막 호출이 window를 벗어난 사용자를 지운다.
 * 그래서 메모리는 최근 window 두 개 안에 tool을 부른 사용자 수에 비례한다.
 *
 * <p>기록은 이 process의 메모리에만 있다.
 * 서버를 여러 대 띄우면 서버마다 따로 센다.
 * 그런 배포에서는 gateway나 공유 저장소에서 센다.
 */
public class ToolCallRateLimiter {

	private final int limit;

	private final Duration window;

	private final Clock clock;

	private final ConcurrentHashMap<String, Deque<Instant>> calls = new ConcurrentHashMap<>();

	private final AtomicReference<Instant> nextSweep;

	public ToolCallRateLimiter(int limit, Duration window, Clock clock) {
		Assert.isTrue(limit > 0, "tool 호출 상한은 0보다 커야 한다: " + limit);
		Assert.isTrue(!window.isNegative() && !window.isZero(), "window는 0보다 길어야 한다: " + window);
		this.limit = limit;
		this.window = window;
		this.clock = clock;
		this.nextSweep = new AtomicReference<>(clock.instant().plus(window));
	}

	/**
	 * 호출 하나를 받아들일 수 있으면 기록하고 빈 값을 돌려준다.
	 * 받아들일 수 없으면 기록하지 않고, 다시 시도할 수 있을 때까지 남은 시간을 돌려준다.
	 */
	public Optional<Duration> tryAcquire(String subject) {
		Instant now = this.clock.instant();
		sweepIfDue(now);
		Instant windowStart = now.minus(this.window);
		Duration[] retryAfter = new Duration[1];
		// compute는 같은 사용자의 갱신을 하나씩 처리한다.
		// 동시에 들어온 호출이 함께 상한을 넘지 못한다.
		this.calls.compute(subject, (key, times) -> {
			Deque<Instant> recent = (times != null) ? times : new ArrayDeque<>();
			while (!recent.isEmpty() && !recent.peekFirst().isAfter(windowStart)) {
				recent.pollFirst();
			}
			if (recent.size() < this.limit) {
				recent.addLast(now);
			}
			else {
				retryAfter[0] = Duration.between(now, recent.peekFirst().plus(this.window));
			}
			return recent;
		});
		return Optional.ofNullable(retryAfter[0]);
	}

	/**
	 * 기록을 가진 사용자 수다.
	 * 정리가 되는지 테스트가 본다.
	 */
	int trackedSubjects() {
		return this.calls.size();
	}

	/**
	 * window마다 한 번, 마지막 호출이 window를 벗어난 사용자의 기록을 지운다.
	 * 한 thread만 정리한다.
	 */
	private void sweepIfDue(Instant now) {
		Instant due = this.nextSweep.get();
		if (now.isBefore(due) || !this.nextSweep.compareAndSet(due, now.plus(this.window))) {
			return;
		}
		Instant windowStart = now.minus(this.window);
		for (String subject : this.calls.keySet()) {
			// 지우는 판단과 지우기를 한 번에 해서, 그사이 들어온 호출의 기록을 지우지 않는다.
			this.calls.computeIfPresent(subject,
					(key, times) -> (times.isEmpty() || !times.peekLast().isAfter(windowStart)) ? null : times);
		}
	}
}
