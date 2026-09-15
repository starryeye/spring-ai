package dev.starryeye.memoryauthn.agent;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.util.Assert;

import java.util.Map;

/**
 * 설정 파일에 인가 서버 주소를 적는 대신, MCP 서버에게 물어서 등록을 만든다.
 *
 * <p>발견은 처음 필요할 때 한 번만 하고 결과를 캐시한다. 기동 시점에 하지 않는 이유는
 * MCP 서버가 아직 떠 있지 않아도 에이전트는 떠야 하기 때문이다. 실패한 발견은
 * 캐시하지 않으므로 다음 요청에서 다시 시도한다.
 *
 * <p>자격증명(client_id/secret)은 특정 인가 서버에 등록된 것이다. 발견 결과가 다른 인가
 * 서버를 가리키면 자격증명을 보내지 않고 멈춘다 — 가짜 인가 서버로 비밀을 흘리지 않기 위해서다.
 */
public class DiscoveredClientRegistrationRepository implements ClientRegistrationRepository {

    private final McpAuthorizationDiscovery discovery;

    private final McpAuthorizationProperties properties;

    private final String registrationId;

    private final OAuth2ClientProperties.Registration credentials;

    private volatile Discovered discovered;

    public DiscoveredClientRegistrationRepository(McpAuthorizationDiscovery discovery,
            McpAuthorizationProperties properties, OAuth2ClientProperties clientProperties) {
        Assert.state(clientProperties.getRegistration().size() == 1,
                "spring.security.oauth2.client.registration 은 정확히 하나여야 한다");
        Map.Entry<String, OAuth2ClientProperties.Registration> registration =
                clientProperties.getRegistration().entrySet().iterator().next();
        this.discovery = discovery;
        this.properties = properties;
        this.registrationId = registration.getKey();
        this.credentials = registration.getValue();
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
        synchronized (this) {
            if (this.discovered == null) {
                DiscoveredAuthorization authorization = this.discovery.discover(this.properties.resourceUrl());
                this.discovered = new Discovered(authorization, registration(authorization));
            }
            return this.discovered;
        }
    }

    private ClientRegistration registration(DiscoveredAuthorization authorization) {
        if (!this.properties.credentialsIssuer().equals(authorization.issuer())) {
            throw new McpDiscoveryException(
                    "자격증명은 %s 에 등록된 것인데 발견한 인가 서버는 %s 다 — 자격증명을 보내지 않는다"
                            .formatted(this.properties.credentialsIssuer(), authorization.issuer()));
        }
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
