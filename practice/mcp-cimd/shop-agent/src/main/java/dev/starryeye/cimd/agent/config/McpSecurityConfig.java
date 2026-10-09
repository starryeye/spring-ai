package dev.starryeye.cimd.agent.config;

import com.nimbusds.jose.jwk.JWK;
import dev.starryeye.cimd.agent.cimd.ClientMetadataProperties;
import dev.starryeye.cimd.agent.cimd.ClientSigningKey;
import dev.starryeye.cimd.agent.discovery.DiscoveredClientRegistrationRepository;
import dev.starryeye.cimd.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.cimd.agent.mcp.OAuth2TokenAttachingRequestCustomizer;
import dev.starryeye.cimd.agent.mcp.SecurityMcpTransportContextProvider;
import dev.starryeye.cimd.agent.mcp.StepUpAuthorizationErrorHandler;
import dev.starryeye.cimd.agent.mcp.StepUpToolExecutionExceptionProcessor;
import dev.starryeye.cimd.agent.security.ResourceIndicators;
import dev.starryeye.cimd.agent.security.SingleFlightAuthorizedClientManager;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.AbstractOAuth2AuthorizationGrantRequest;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.function.Function;

/**
 * MCP 호출에 쓸 token을 준비하는 bean을 등록한다.
 *
 * <p>Authorization Server의 endpoint는 설정이 아니라 discovery에서 온다.
 * token request와 refresh 요청에는 RFC 8707 {@code resource}를 넣는다.
 * 그래서 발급되는 token은 이 MCP Server 전용이 된다.
 * ChatGPT형이면 두 요청 모두에 서명한 client assertion을 붙인다.
 */
@Configuration
@EnableConfigurationProperties(McpAuthorizationProperties.class)
public class McpSecurityConfig {

    /** login 경로(/oauth2/authorization/authserver)와 callback 경로에 쓰는 registration id다. */
    public static final String REGISTRATION_ID = "authserver";

    @Bean
    public McpAuthorizationDiscovery mcpAuthorizationDiscovery() {
        return new McpAuthorizationDiscovery(RestClient.create());
    }

    @Bean
    public DiscoveredClientRegistrationRepository clientRegistrationRepository(McpAuthorizationDiscovery discovery,
            McpAuthorizationProperties properties, ClientMetadataProperties clientMetadata) {
        return new DiscoveredClientRegistrationRepository(discovery, properties, clientMetadata, REGISTRATION_ID);
    }

    /**
     * authorized client를 session이 아니라 서비스에 저장한다.
     * servlet 요청 없이 {@code Authentication}만으로 token을 꺼낼 수 있어야
     * reactor thread에서도 token을 붙인다.
     */
    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    /** login(code 교환) 때 token request를 보내는 client다. resource와, ChatGPT형이면 assertion을 넣는다. */
    @Bean
    public RestClientAuthorizationCodeTokenResponseClient authorizationCodeTokenResponseClient(
            DiscoveredClientRegistrationRepository registrations, ClientSigningKey signingKey) {
        var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenResponseClient.addParametersConverter(
                ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
        tokenResponseClient.addParametersConverter(clientAssertion(signingKey));
        return tokenResponseClient;
    }

    /** access token이 만료된 뒤 refresh 요청을 보내는 client다. 여기에도 resource와 assertion이 필요하다. */
    @Bean
    public RestClientRefreshTokenTokenResponseClient refreshTokenTokenResponseClient(
            DiscoveredClientRegistrationRepository registrations, ClientSigningKey signingKey) {
        var tokenResponseClient = new RestClientRefreshTokenTokenResponseClient();
        tokenResponseClient.addParametersConverter(
                ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
        tokenResponseClient.addParametersConverter(clientAssertion(signingKey));
        return tokenResponseClient;
    }

    /** RFC 7523bis가 권하는 client assertion의 {@code typ}이다. */
    static final String CLIENT_ASSERTION_TYPE = "client-authentication+jwt";

    /**
     * token request에 client assertion을 붙이는 converter다.
     *
     * <p>assertion의 iss·sub는 client_id이고(Spring 기본값), aud는 Authorization Server의 issuer 하나다.
     * Spring 기본값은 aud에 token endpoint를 넣지만, RFC 7523bis는 issuer 하나만 쓰게 한다.
     * 이 agent는 discovery로 처음 보는 Authorization Server에도 같은 key로 assertion을 보낸다.
     * endpoint 주소는 그 서버의 metadata가 알려 주는 값이라, aud에 쓰면 assertion이 어느 서버를 위한 것인지가 metadata에 달린다.
     * issuer는 discovery에서 PRM이 가리킨 값과 metadata의 값이 같은지 확인한 값이다.
     */
    static <T extends AbstractOAuth2AuthorizationGrantRequest> NimbusJwtClientAuthenticationParametersConverter<T> clientAssertion(
            ClientSigningKey signingKey) {
        var converter = new NimbusJwtClientAuthenticationParametersConverter<T>(clientAssertionKey(signingKey));
        converter.setJwtClientAssertionCustomizer(context -> {
            String issuer = context.getAuthorizationGrantRequest().getClientRegistration().getProviderDetails()
                    .getIssuerUri();
            if (issuer == null) {
                throw new IllegalStateException("issuer를 모르는 Authorization Server에는 client assertion을 보내지 않는다");
            }
            context.getHeaders().type(CLIENT_ASSERTION_TYPE);
            context.getClaims().audience(List.of(issuer));
        });
        return converter;
    }

    /**
     * client assertion을 서명할 key를 고른다(RFC 7523 §2.2).
     * Authorization Server는 문서의 {@code jwks_uri}에서 같은 key의 public key를 가져와 서명을 확인한다.
     * Claude형({@code none})이면 converter가 parameter를 더하지 않으므로 key를 주지 않는다.
     */
    static Function<ClientRegistration, JWK> clientAssertionKey(ClientSigningKey signingKey) {
        return registration -> ClientAuthenticationMethod.PRIVATE_KEY_JWT
                .equals(registration.getClientAuthenticationMethod()) ? signingKey.key() : null;
    }

    /**
     * 이 manager의 기본 구성에는 refresh가 없다.
     * refresh provider를 직접 넣어야 만료된 token을 새로 받는다.
     * 같은 사용자의 refresh가 겹치지 않도록 {@link SingleFlightAuthorizedClientManager}로 감싼다.
     *
     * <p>테스트가 이 메서드를 직접 불러야 해서 static으로 둔다.
     * Spring은 static {@code @Bean} 메서드도 지원한다.
     * 세 번째 인자에는 {@code RestClientRefreshTokenTokenResponseClient} bean이 주입된다.
     * 이 구체 타입을 인자 타입에 대입할 수 있기 때문이다.
     */
    @Bean
    static OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenTokenResponseClient) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository,
                authorizedClientService);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken(refreshToken -> refreshToken.accessTokenResponseClient(refreshTokenTokenResponseClient))
                .build());
        return new SingleFlightAuthorizedClientManager(manager);
    }

    /** 모든 MCP sync client에 인증을 전달하는 transport context provider를 넣는다. */
    @Bean
    public McpClientCustomizer<McpClient.SyncSpec> mcpAuthenticationCustomizer() {
        return (name, spec) -> spec.transportContextProvider(new SecurityMcpTransportContextProvider());
    }

    /** 모든 Streamable HTTP transport에 token을 붙이고, 403 insufficient_scope를 step-up 예외로 올린다. */
    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpTokenAttachingCustomizer(
            OAuth2AuthorizedClientManager authorizedClientManager) {
        return (name, transport) -> transport
                .httpRequestCustomizer(new OAuth2TokenAttachingRequestCustomizer(authorizedClientManager, REGISTRATION_ID))
                .authorizationErrorHandler(new StepUpAuthorizationErrorHandler());
    }

    /**
     * step-up 예외는 채팅까지 올리고, 나머지는 Spring AI 기본 처리와 같게 둔다.
     * 기본 처리는 Spring Security의 {@code ClientAuthorizationException}을 다시 던지고, 그 밖의 예외는 LLM에게 줄 문장으로 바꾼다.
     * 이 bean이 있으면 Spring AI 자동 구성의 같은 bean은 만들어지지 않는다.
     */
    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return new StepUpToolExecutionExceptionProcessor(DefaultToolExecutionExceptionProcessor.builder()
                .rethrowExceptions(List.of(ClientAuthorizationException.class))
                .build());
    }
}
