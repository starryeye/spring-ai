package dev.starryeye.cimd.agent.discovery;

import dev.starryeye.cimd.agent.cimd.ClientMetadataProperties;
import dev.starryeye.cimd.agent.cimd.ClientType;
import dev.starryeye.cimd.agent.config.McpAuthorizationProperties;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 설정 파일에 Authorization Server의 endpoint를 적는 대신, MCP Server에 물어서 client 등록 정보를 만든다.
 *
 * <p>discovery는 처음 필요할 때 한 번만 하고 결과를 cache한다.
 * 기동할 때 하지 않는 것은 MCP Server가 아직 떠 있지 않아도 agent는 떠야 하기 때문이다.
 *
 * <p>discovery는 login하지 않은 요청에서도 시작된다.
 * login page가 곧 authorization request라서 등록 정보가 필요하기 때문이다.
 * 그래서 MCP Server가 느리거나 응답하지 않아도 요청 thread가 서로를 붙잡지 않게 한다.
 * <ul>
 *   <li>discovery는 요청 thread가 아닌 별도 thread에서 한 번만 돈다.
 *       동시에 들어온 요청은 같은 {@link CompletableFuture}를 기다린다.
 *       lock은 상태를 바꾸는 동안만 잡고, 네트워크 I/O는 lock 밖에서 한다</li>
 *   <li>요청은 {@code timeout}까지만 기다린다.
 *       discovery도 그 시간이 지나면 실패로 끝낸다</li>
 *   <li>실패하면 {@code backoff} 동안은 다시 시도하지 않고 바로 실패한다.
 *       익명 요청이 들어올 때마다 MCP Server와 Authorization Server로 요청이 다시 나가지 않게 하기 위해서다.
 *       그 시간이 지난 뒤의 첫 요청이 다시 시도한다</li>
 * </ul>
 *
 * <p>client_id는 agent가 올린 client 문서의 주소다.
 * Authorization Server에 미리 등록하지 않으므로 client_secret이 없다.
 * ChatGPT형은 token request에 sign한 assertion을 붙이고({@code McpSecurityConfig}), Claude형은 client_id만 보낸다.
 * redirect 주소는 문서에 올린 값 그대로 쓴다.
 * 요청 host로 주소를 만들면 문서의 목록과 달라질 수 있다.
 */
public class DiscoveredClientRegistrationRepository implements ClientRegistrationRepository {

	/**
	 * 요청이 discovery를 기다리는 최대 시간이다.
	 * discovery 한 번도 이 시간 안에 끝나야 한다.
	 */
	static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

	/** discovery가 실패한 뒤 다시 시도하지 않는 시간이다. */
	static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(10);

	/**
	 * discovery를 돌리는 thread다.
	 * 한 번에 하나만 돌므로 시도마다 virtual thread 하나를 쓴다.
	 */
	private static final Executor DISCOVERY_THREAD = task -> Thread.ofVirtual().name("mcp-discovery").start(task);

	private final McpAuthorizationDiscovery discovery;

	private final McpAuthorizationProperties properties;

	private final ClientMetadataProperties clientMetadata;

	private final String registrationId;

	private final Duration timeout;

	private final Duration backoff;

	private final Clock clock;

	private volatile Discovered discovered;

	// 아래 세 field는 this의 monitor 안에서만 읽고 바꾼다.

	/**
	 * 진행 중인 discovery다.
	 * 없으면 null이다.
	 */
	private CompletableFuture<Discovered> inFlight;

	/**
	 * 마지막 실패다.
	 * {@link #retryAt}까지는 다시 시도하지 않고 이 실패를 알린다.
	 */
	private McpDiscoveryException lastFailure;

	private Instant retryAt = Instant.MIN;

	public DiscoveredClientRegistrationRepository(McpAuthorizationDiscovery discovery,
			McpAuthorizationProperties properties, ClientMetadataProperties clientMetadata, String registrationId) {
		this(discovery, properties, clientMetadata, registrationId, DEFAULT_TIMEOUT, DEFAULT_BACKOFF, Clock.systemUTC());
	}

	DiscoveredClientRegistrationRepository(McpAuthorizationDiscovery discovery,
			McpAuthorizationProperties properties, ClientMetadataProperties clientMetadata, String registrationId,
			Duration timeout, Duration backoff, Clock clock) {
		this.discovery = discovery;
		this.properties = properties;
		this.clientMetadata = clientMetadata;
		this.registrationId = registrationId;
		this.timeout = timeout;
		this.backoff = backoff;
		this.clock = clock;
	}

	@Override
	public ClientRegistration findByRegistrationId(String registrationId) {
		return this.registrationId.equals(registrationId) ? state().registration() : null;
	}

	/**
	 * discovery로 찾은 resource 식별자와 Authorization Server metadata다.
	 * {@code resource} parameter와 iss 검증이 쓴다.
	 */
	public DiscoveredAuthorization discovered() {
		return state().authorization();
	}

	private Discovered state() {
		Discovered current = this.discovered;
		if (current != null) {
			return current;
		}
		CompletableFuture<Discovered> attempt = attempt();
		try {
			return attempt.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			throw new McpDiscoveryException("discovery가 %d ms 안에 끝나지 않았다".formatted(this.timeout.toMillis()), ex);
		}
		catch (ExecutionException ex) {
			McpDiscoveryException failure = failure(ex.getCause());
			throw new McpDiscoveryException(failure.getMessage(), failure);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new McpDiscoveryException("discovery를 기다리다 중단됐다", ex);
		}
	}

	/**
	 * 진행 중인 discovery가 있으면 그것을, 없으면 새로 시작한 것을 돌려준다.
	 * monitor 안에서는 상태만 보고 바꾼다.
	 * discovery 자체는 {@link #DISCOVERY_THREAD}에서 돈다.
	 */
	private synchronized CompletableFuture<Discovered> attempt() {
		if (this.discovered != null) {
			return CompletableFuture.completedFuture(this.discovered);
		}
		if (this.inFlight != null) {
			return this.inFlight;
		}
		if (this.lastFailure != null && this.clock.instant().isBefore(this.retryAt)) {
			throw new McpDiscoveryException("discovery가 실패해 %s까지 다시 시도하지 않는다: %s"
					.formatted(this.retryAt, this.lastFailure.getMessage()), this.lastFailure);
		}
		CompletableFuture<Discovered> attempt = CompletableFuture.supplyAsync(this::discover, DISCOVERY_THREAD)
				.orTimeout(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
		this.inFlight = attempt;
		attempt.whenComplete((result, error) -> settle(attempt, result, error));
		return attempt;
	}

	private Discovered discover() {
		DiscoveredAuthorization authorization = this.discovery.discover(this.properties.resourceUrl(),
				this.properties.clientType().authenticationMethod());
		return new Discovered(authorization, registration(authorization));
	}

	/** 성공하면 결과를 기억하고, 실패하면 {@code backoff} 동안 다시 시도하지 않도록 기록한다. */
	private synchronized void settle(CompletableFuture<Discovered> attempt, Discovered result, Throwable error) {
		if (this.inFlight != attempt) {
			return;
		}
		this.inFlight = null;
		if (error == null) {
			this.discovered = result;
			this.lastFailure = null;
			return;
		}
		this.lastFailure = failure(error);
		this.retryAt = this.clock.instant().plus(this.backoff);
	}

	private McpDiscoveryException failure(Throwable error) {
		Throwable cause = (error instanceof CompletionException && error.getCause() != null) ? error.getCause() : error;
		if (cause instanceof McpDiscoveryException discoveryFailure) {
			return discoveryFailure;
		}
		if (cause instanceof TimeoutException) {
			return new McpDiscoveryException("discovery가 %d ms 안에 끝나지 않았다".formatted(this.timeout.toMillis()), cause);
		}
		return new McpDiscoveryException("discovery가 실패했다: " + cause.getMessage(), cause);
	}

	private ClientRegistration registration(DiscoveredAuthorization authorization) {
		ClientType clientType = this.properties.clientType();
		return ClientRegistration.withRegistrationId(this.registrationId)
				.clientId(clientType.clientId(this.clientMetadata.baseUrl()))
				.clientAuthenticationMethod(clientType.authenticationMethod())
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri(this.clientMetadata.redirectUri())
				.scope(requestedScopes(authorization))
				.authorizationUri(authorization.authorizationEndpoint())
				.tokenUri(authorization.tokenEndpoint())
				.jwkSetUri(authorization.jwksUri())
				.issuerUri(authorization.issuer())
				.providerConfigurationMetadata(authorization.authorizationServerMetadata())
				.userNameAttributeName(IdTokenClaimNames.SUB)
				.clientName(clientType.clientName())
				.build();
	}

	/** login에는 {@code openid}가 필요하고, MCP 호출에 쓸 scope는 discovery가 고른다. */
	private static Set<String> requestedScopes(DiscoveredAuthorization authorization) {
		Set<String> scopes = new LinkedHashSet<>();
		scopes.add(OidcScopes.OPENID);
		scopes.addAll(authorization.scopes());
		return scopes;
	}

	private record Discovered(DiscoveredAuthorization authorization, ClientRegistration registration) {
	}
}
