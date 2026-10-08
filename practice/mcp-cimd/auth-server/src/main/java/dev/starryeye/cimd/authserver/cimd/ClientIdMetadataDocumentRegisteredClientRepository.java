package dev.starryeye.cimd.authserver.cimd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CIMD client 저장소다. 미리 등록한 client는 없고, 문서 주소가 곧 client_id다.
 *
 * <p>처음 보는 client_id를 만나면 주소를 검사하고, 문서를 가져와 검사한 뒤 {@link RegisteredClient}로 바꾼다.
 * 문서는 HTTP cache header를 따라 cache하되 상한을 둔다(CIMD draft §4.4).
 * 가져오지 못했거나 잘못된 문서는 cache하지 않는다. client가 문서를 고치면 다음 요청에서 바로 다시 가져온다.
 *
 * <p>{@code id}도 문서 주소로 둔다. Spring은 저장한 authorization의 client를 {@link #findById}로 다시 찾는데,
 * 그때도 같은 문서에서 같은 client를 만들 수 있어야 하기 때문이다.
 * 인증 방식은 문서의 {@code token_endpoint_auth_method} 하나뿐이다. 그래서 {@code private_key_jwt}를 선언한 client는
 * {@code none}으로 인증할 수 없다.
 */
public final class ClientIdMetadataDocumentRegisteredClientRepository implements RegisteredClientRepository {

	private static final Logger log = LoggerFactory.getLogger(ClientIdMetadataDocumentRegisteredClientRepository.class);

	private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
	};

	/**
	 * cache에 두는 client 수의 상한이다.
	 * client_id는 요청하는 쪽이 정하는 주소여서, 문서를 올릴 수 있는 사람은 서로 다른 주소를 얼마든지 만들 수 있다.
	 * 상한이 없으면 인증 없이 보내는 authorize 요청만으로 memory가 바닥난다.
	 */
	private static final int MAX_CACHE_ENTRIES = 1000;

	/** 로그 한 줄에 남기는 외부 값의 최대 길이다. 문서와 요청의 값은 그대로 믿을 수 없다. */
	private static final int MAX_LOG_VALUE_LENGTH = 200;

	private final ClientIdUrlValidator urlValidator;

	private final ClientMetadataValidator metadataValidator;

	private final ClientMetadataHttp http;

	private final ClientIdMetadataDocumentProperties properties;

	private final Clock clock;

	private final int maxCacheEntries;

	private final JsonMapper json = JsonMapper.builder().build();

	private final Map<String, Entry> cache = new ConcurrentHashMap<>();

	public ClientIdMetadataDocumentRegisteredClientRepository(ClientIdUrlValidator urlValidator, ClientMetadataHttp http,
			ClientIdMetadataDocumentProperties properties, Clock clock) {
		this(urlValidator, http, properties, clock, MAX_CACHE_ENTRIES);
	}

	/** 테스트가 상한을 작게 잡을 수 있도록 열어 둔다. */
	ClientIdMetadataDocumentRegisteredClientRepository(ClientIdUrlValidator urlValidator, ClientMetadataHttp http,
			ClientIdMetadataDocumentProperties properties, Clock clock, int maxCacheEntries) {
		this.maxCacheEntries = maxCacheEntries;
		this.urlValidator = urlValidator;
		this.metadataValidator = new ClientMetadataValidator(urlValidator);
		this.http = http;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void save(RegisteredClient registeredClient) {
		throw new UnsupportedOperationException("CIMD client는 등록하지 않는다. 문서 주소가 곧 client_id다");
	}

	@Override
	public RegisteredClient findById(String id) {
		return findByClientId(id);
	}

	@Override
	public RegisteredClient findByClientId(String clientId) {
		if (clientId == null || !clientId.contains("://")) {
			return null;
		}
		Instant now = this.clock.instant();
		Entry cached = this.cache.get(clientId);
		if (cached != null && now.isBefore(cached.expiresAt())) {
			log.debug("client 문서를 cache에서 꺼낸다 (client_id={})", forLog(clientId));
			return cached.client();
		}
		if (cached != null) {
			this.cache.remove(clientId, cached);
		}
		try {
			URI url = this.urlValidator.validate(clientId);
			FetchedDocument fetched = this.http.get(url);
			ClientMetadata metadata = this.metadataValidator.validate(url, parse(fetched.body()));
			RegisteredClient client = toRegisteredClient(metadata);
			Duration ttl = cacheTtl(fetched);
			boolean stored = !ttl.isZero() && remember(clientId, new Entry(client, now.plus(ttl)), now);
			log.info("client 문서를 가져왔다 (client_id={}, 인증 방식={}, cache={}초)", forLog(clientId),
					metadata.authenticationMethod().getValue(), stored ? ttl.toSeconds() : 0);
			return client;
		}
		catch (InvalidClientMetadataException ex) {
			log.warn("client 문서를 쓸 수 없다 (client_id={}, 이유={})", forLog(clientId), forLog(ex.getMessage()));
			return null;
		}
	}

	/**
	 * 만료된 항목을 먼저 치우고, 그래도 가득 차 있으면 새 client는 cache하지 않는다(요청에는 그대로 돌려준다).
	 * 만료된 항목은 같은 client_id가 다시 오지 않으면 남아 있으므로 새로 넣을 때 함께 치운다.
	 */
	private synchronized boolean remember(String clientId, Entry entry, Instant now) {
		this.cache.values().removeIf((existing) -> !now.isBefore(existing.expiresAt()));
		if (this.cache.size() >= this.maxCacheEntries && !this.cache.containsKey(clientId)) {
			log.warn("client 문서 cache가 가득 차서 cache하지 않는다 (상한={})", this.maxCacheEntries);
			return false;
		}
		this.cache.put(clientId, entry);
		return true;
	}

	int cacheSize() {
		return this.cache.size();
	}

	/**
	 * 로그에 남길 값에서 줄바꿈 같은 제어 문자를 없애고 길이를 줄인다.
	 * client_id와 문서의 값은 요청하는 쪽이 정하므로, 그대로 쓰면 로그에 가짜 줄을 끼워 넣을 수 있다.
	 */
	static String forLog(String value) {
		if (value == null) {
			return "null";
		}
		int length = Math.min(value.length(), MAX_LOG_VALUE_LENGTH);
		if (length < value.length() && Character.isHighSurrogate(value.charAt(length - 1))) {
			length--;
		}
		StringBuilder safe = new StringBuilder(length + 8);
		for (int i = 0; i < length; i++) {
			char c = value.charAt(i);
			int type = Character.getType(c);
			boolean control = Character.isISOControl(c) || type == Character.LINE_SEPARATOR
					|| type == Character.PARAGRAPH_SEPARATOR;
			safe.append(control ? '?' : c);
		}
		if (length < value.length()) {
			safe.append("...(").append(value.length() - length).append("자 생략)");
		}
		return safe.toString();
	}

	private Map<String, Object> parse(byte[] body) {
		JsonNode node;
		try {
			node = this.json.readTree(body);
		}
		catch (JacksonException ex) {
			throw new InvalidClientMetadataException("문서가 JSON이 아니다", ex);
		}
		if (node == null || !node.isObject()) {
			throw new InvalidClientMetadataException("문서가 JSON object 하나가 아니다");
		}
		return this.json.convertValue(node, JSON_OBJECT);
	}

	private Duration cacheTtl(FetchedDocument fetched) {
		if (fetched.noStore()) {
			return Duration.ZERO;
		}
		Duration ttl = (fetched.maxAge() != null) ? fetched.maxAge() : this.properties.defaultCacheTtl();
		return (ttl.compareTo(this.properties.maxCacheTtl()) > 0) ? this.properties.maxCacheTtl() : ttl;
	}

	private RegisteredClient toRegisteredClient(ClientMetadata metadata) {
		ClientSettings.Builder settings = ClientSettings.builder()
				// 비밀이 없거나(none) 처음 보는 client이므로, 가로챈 code를 쓰지 못하게 PKCE를 반드시 쓰게 한다.
				.requireProofKey(true)
				// 미리 맺은 관계가 없으므로 사용자가 누구에게 무엇을 허락하는지 직접 보게 한다.
				.requireAuthorizationConsent(true);
		if (metadata.jwksUri() != null) {
			settings.jwkSetUrl(metadata.jwksUri())
					.tokenEndpointAuthenticationSigningAlgorithm(SignatureAlgorithm.RS256);
		}
		return RegisteredClient.withId(metadata.clientId())
				.clientId(metadata.clientId())
				.clientName(metadata.clientName())
				.clientAuthenticationMethod(metadata.authenticationMethod())
				.authorizationGrantTypes(grantTypes -> grantTypes.addAll(metadata.grantTypes()))
				.redirectUris(redirectUris -> redirectUris.addAll(metadata.redirectUris()))
				.scopes(scopes -> scopes.addAll(this.properties.scopes()))
				.clientSettings(settings.build())
				// refresh할 때마다 새 refresh token을 주고 옛것은 버린다(rotation).
				// public client에게 refresh token을 주는 조건이다(OAuth 2.1 §4.3.1).
				.tokenSettings(TokenSettings.builder().reuseRefreshTokens(false).build())
				.build();
	}

	private record Entry(RegisteredClient client, Instant expiresAt) {
	}
}
