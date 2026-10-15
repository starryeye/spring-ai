package dev.starryeye.cimd.agent.security;

import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 같은 사용자의 token 요청을 한 번에 하나씩 처리해, 만료된 token의 refresh가 겹치지 않게 한다.
 *
 * <p>Authorization Server는 refresh할 때마다 새 refresh token을 주고 옛것을 버린다(rotation).
 * 버린 refresh token이 다시 오면 도난으로 보고 그 grant 전체를 끊는다.
 * 한 사용자의 MCP 요청 둘이 동시에 만료된 token을 보면, 둘 다 같은 refresh token으로 refresh를 보낸다.
 * 늦게 간 요청은 버린 token을 내민 셈이 되어, 사용자의 grant가 끊긴다.
 *
 * <p>이 manager는 사용자와 registration마다 lock을 두고 감싼 manager를 부른다.
 * 먼저 들어간 요청이 refresh해 저장하면, 기다리던 요청은 저장된 새 token을 받는다.
 * 사용자가 다르면 서로 기다리지 않는다.
 * lock은 그 lock을 쓰거나 기다리는 요청이 없어지면 map에서 지운다. 사용자가 늘어도 map이 계속 커지지 않는다.
 * virtual thread를 켜도({@code spring.threads.virtual.enabled}) 기다리는 동안 carrier thread를 붙잡지 않도록
 * {@code synchronized} 대신 {@link ReentrantLock}을 쓴다.
 */
public class SingleFlightAuthorizedClientManager implements OAuth2AuthorizedClientManager {

	private final OAuth2AuthorizedClientManager delegate;

	private final ConcurrentHashMap<String, UserLock> locks = new ConcurrentHashMap<>();

	public SingleFlightAuthorizedClientManager(OAuth2AuthorizedClientManager delegate) {
		this.delegate = delegate;
	}

	@Override
	public OAuth2AuthorizedClient authorize(OAuth2AuthorizeRequest authorizeRequest) {
		String key = authorizeRequest.getClientRegistrationId() + ":" + authorizeRequest.getPrincipal().getName();
		// 쓰는 요청 수는 map의 compute 안에서만 바꾼다. 그래야 지우는 순간에 다른 요청이 같은 lock을 잡지 않는다.
		UserLock userLock = this.locks.compute(key, (ignored, existing) -> {
			UserLock current = (existing != null) ? existing : new UserLock();
			current.users++;
			return current;
		});
		userLock.lock.lock();
		try {
			return this.delegate.authorize(authorizeRequest);
		}
		finally {
			userLock.lock.unlock();
			this.locks.computeIfPresent(key, (ignored, current) -> (--current.users == 0) ? null : current);
		}
	}

	/** 지금 map에 남아 있는 lock 수다. 테스트가 정리를 확인할 때 쓴다. */
	int lockCount() {
		return this.locks.size();
	}

	/** 사용자 하나의 lock과, 그 lock을 쓰거나 기다리는 요청 수다. */
	private static final class UserLock {

		private final ReentrantLock lock = new ReentrantLock();

		/** map의 compute 안에서만 읽고 바꾼다. */
		private int users;
	}
}
