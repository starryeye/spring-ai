package dev.starryeye.modern.authserver.security;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 테스트 중에 client를 바꿔 끼울 수 있는 저장소다. 문서가 바뀌는 상황을 흉내 낸다. id와 client_id는 같다고 본다. */
final class MutableRegisteredClientRepository implements RegisteredClientRepository {

	private final Map<String, RegisteredClient> clients = new ConcurrentHashMap<>();

	void put(RegisteredClient client) {
		this.clients.put(client.getId(), client);
	}

	@Override
	public void save(RegisteredClient registeredClient) {
		put(registeredClient);
	}

	@Override
	public RegisteredClient findById(String id) {
		return this.clients.get(id);
	}

	@Override
	public RegisteredClient findByClientId(String clientId) {
		return this.clients.values().stream()
				.filter(client -> client.getClientId().equals(clientId))
				.findFirst()
				.orElse(null);
	}
}
