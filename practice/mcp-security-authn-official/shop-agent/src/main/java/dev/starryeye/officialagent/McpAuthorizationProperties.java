package dev.starryeye.officialagent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param resourceUrl protected resource(MCP endpoint)의 URL. discovery는 여기서 시작한다
 * @param credentialsIssuer {@code spring.security.oauth2.client.registration}의 client-id/secret이 등록된
 *        Authorization Server. discovery 결과가 이것과 다르면 credentials를 보내지 않는다
 *        (MCP 2026-07-28: credentials는 issuer에 묶인다)
 */
@ConfigurationProperties("mcp.authorization")
public record McpAuthorizationProperties(String resourceUrl, String credentialsIssuer) {
}
