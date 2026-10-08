package dev.starryeye.cimd.agent.discovery;

import dev.starryeye.cimd.agent.cimd.ClientMetadataProperties;
import dev.starryeye.cimd.agent.cimd.ClientType;
import dev.starryeye.cimd.agent.config.McpAuthorizationProperties;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 설정 파일에 Authorization Server의 endpoint를 적는 대신, MCP Server에 물어서 client 등록 정보를 만든다.
 *
 * <p>discovery는 처음 필요할 때 한 번만 하고 결과를 cache한다.
 * 기동할 때 하지 않는 것은 MCP Server가 아직 떠 있지 않아도 agent는 떠야 하기 때문이다.
 * 실패한 discovery는 cache하지 않으므로 다음 요청에서 다시 시도한다.
 *
 * <p>client_id는 agent가 올린 client 문서의 주소다.
 * Authorization Server에 미리 등록하지 않으므로 client_secret이 없다.
 * ChatGPT형은 token request에 서명한 assertion을 붙이고({@code McpSecurityConfig}), Claude형은 client_id만 보낸다.
 * redirect 주소는 문서에 올린 값 그대로 쓴다. 요청 host로 주소를 만들면 문서의 목록과 달라질 수 있다.
 */
public class DiscoveredClientRegistrationRepository implements ClientRegistrationRepository {

	private final McpAuthorizationDiscovery discovery;

	private final McpAuthorizationProperties properties;

	private final ClientMetadataProperties clientMetadata;

	private final String registrationId;

	private volatile Discovered discovered;

	public DiscoveredClientRegistrationRepository(McpAuthorizationDiscovery discovery,
			McpAuthorizationProperties properties, ClientMetadataProperties clientMetadata, String registrationId) {
		this.discovery = discovery;
		this.properties = properties;
		this.clientMetadata = clientMetadata;
		this.registrationId = registrationId;
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
		synchronized (this) {
			if (this.discovered == null) {
				DiscoveredAuthorization authorization = this.discovery.discover(this.properties.resourceUrl(),
						this.properties.clientType().authenticationMethod());
				this.discovered = new Discovered(authorization, registration(authorization));
			}
			return this.discovered;
		}
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
