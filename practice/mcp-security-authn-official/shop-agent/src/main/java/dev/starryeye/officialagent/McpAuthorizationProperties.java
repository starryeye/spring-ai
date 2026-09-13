package dev.starryeye.officialagent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param resourceUrl 보호 리소스(MCP 엔드포인트)의 URL. 발견은 여기서 시작한다
 * @param credentialsIssuer 아래 client-id/secret 이 등록된 인가 서버.
 *        발견 결과가 이것과 다르면 자격증명을 보내지 않는다(MCP 2026-07-28: 자격증명은 issuer 에 묶인다)
 */
@ConfigurationProperties("mcp.authorization")
public record McpAuthorizationProperties(String resourceUrl, String credentialsIssuer) {
}
