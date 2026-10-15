package dev.starryeye.memory.agent.discovery;

import dev.starryeye.memory.agent.config.McpAuthorizationProperties;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.util.Assert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 설정 파일에 인가 서버 주소를 적는 대신, MCP 서버에게 물어서 등록을 만든다.
 *
 * <p>발견은 처음 필요할 때 한 번만 하고 결과를 캐시한다. 기동 시점에 하지 않는 이유는
 * MCP 서버가 아직 떠 있지 않아도 에이전트는 떠야 하기 때문이다.
 *
 * <p>discovery는 login하지 않은 요청에서도 시작된다. login page가 곧 authorization request라서
 * 등록 정보가 필요하기 때문이다. 그래서 MCP Server가 느리거나 응답하지 않아도 요청 thread가 서로를 붙잡지 않게 한다.
 * <ul>
 *   <li>discovery는 요청 thread가 아닌 별도 thread에서 한 번만 돈다. 동시에 들어온 요청은 같은
 *       {@link CompletableFuture}를 기다린다. lock은 상태를 바꾸는 동안만 잡고, 네트워크 I/O는 lock 밖에서 한다</li>
 *   <li>요청은 {@code timeout}까지만 기다린다. discovery도 그 시간이 지나면 실패로 끝낸다</li>
 *   <li>실패하면 {@code backoff} 동안은 다시 시도하지 않고 바로 실패한다.
 *       익명 요청이 들어올 때마다 MCP Server와 Authorization Server로 요청이 다시 나가지 않게 하기 위해서다.
 *       그 시간이 지난 뒤의 첫 요청이 다시 시도한다</li>
 * </ul>
 *
 * <p>자격증명(client_id/secret)은 특정 인가 서버에 등록된 것이다. 발견이 다른 인가 서버를 가리키면
 * 그 메타데이터도 요청하지 않고 멈춘다({@link McpAuthorizationDiscovery#discover(String, String)}) —
 * 가짜 인가 서버로 비밀을 흘리지도, 공격자가 고른 주소로 요청을 보내지도 않기 위해서다.
 */
public class DiscoveredClientRegistrationRepository implements ClientRegistrationRepository {

    /** 요청이 discovery를 기다리는 최대 시간이다. discovery 한 번도 이 시간 안에 끝나야 한다. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** discovery가 실패한 뒤 다시 시도하지 않는 시간이다. */
    static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(10);

    /** discovery를 돌리는 thread다. 한 번에 하나만 돌므로 시도마다 virtual thread 하나를 쓴다. */
    private static final Executor DISCOVERY_THREAD = task -> Thread.ofVirtual().name("mcp-discovery").start(task);

    private final McpAuthorizationDiscovery discovery;

    private final McpAuthorizationProperties properties;

    private final String registrationId;

    private final OAuth2ClientProperties.Registration credentials;

    private final Duration timeout;

    private final Duration backoff;

    private final Clock clock;

    private volatile Discovered discovered;

    // 아래 세 field는 this의 monitor 안에서만 읽고 바꾼다.

    /** 진행 중인 discovery다. 없으면 null이다. */
    private CompletableFuture<Discovered> inFlight;

    /** 마지막 실패다. {@link #retryAt}까지는 다시 시도하지 않고 이 실패를 알린다. */
    private McpDiscoveryException lastFailure;

    private Instant retryAt = Instant.MIN;

    public DiscoveredClientRegistrationRepository(McpAuthorizationDiscovery discovery,
            McpAuthorizationProperties properties, OAuth2ClientProperties clientProperties) {
        this(discovery, properties, clientProperties, DEFAULT_TIMEOUT, DEFAULT_BACKOFF, Clock.systemUTC());
    }

    DiscoveredClientRegistrationRepository(McpAuthorizationDiscovery discovery,
            McpAuthorizationProperties properties, OAuth2ClientProperties clientProperties,
            Duration timeout, Duration backoff, Clock clock) {
        Assert.state(clientProperties.getRegistration().size() == 1,
                "spring.security.oauth2.client.registration 은 정확히 하나여야 한다");
        Map.Entry<String, OAuth2ClientProperties.Registration> registration =
                clientProperties.getRegistration().entrySet().iterator().next();
        this.discovery = discovery;
        this.properties = properties;
        this.registrationId = registration.getKey();
        this.credentials = registration.getValue();
        this.timeout = timeout;
        this.backoff = backoff;
        this.clock = clock;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        return this.registrationId.equals(registrationId) ? state().registration() : null;
    }

    /** 발견한 리소스 식별자·인가 서버 메타데이터. {@code resource} 파라미터와 iss 검증이 쓴다. */
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
            throw new McpDiscoveryException("discovery가 %s 안에 끝나지 않았다".formatted(this.timeout), ex);
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
     * monitor 안에서는 상태만 보고 바꾼다. discovery 자체는 {@link #DISCOVERY_THREAD}에서 돈다.
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
                this.properties.credentialsIssuer());
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
            return new McpDiscoveryException("discovery가 %s 안에 끝나지 않았다".formatted(this.timeout), cause);
        }
        return new McpDiscoveryException("discovery가 실패했다: " + cause.getMessage(), cause);
    }

    private ClientRegistration registration(DiscoveredAuthorization authorization) {
        return ClientRegistration.withRegistrationId(this.registrationId)
                .clientId(this.credentials.getClientId())
                .clientSecret(this.credentials.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(this.credentials.getRedirectUri())
                .scope(this.credentials.getScope())
                .authorizationUri(authorization.authorizationEndpoint())
                .tokenUri(authorization.tokenEndpoint())
                .jwkSetUri(authorization.jwksUri())
                .issuerUri(authorization.issuer())
                .providerConfigurationMetadata(authorization.authorizationServerMetadata())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName(this.registrationId)
                .build();
    }

    private record Discovered(DiscoveredAuthorization authorization, ClientRegistration registration) {
    }
}
