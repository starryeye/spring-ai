package dev.starryeye.cimd.agent.config;

import dev.starryeye.cimd.agent.cimd.ClientType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param resourceUrl protected resource(MCP endpoint)의 URL. discovery는 여기서 시작한다
 * @param clientType 어느 제품처럼 붙을지 정한다. 문서 주소(client_id)와 token endpoint 인증 방식이 따라온다
 */
@ConfigurationProperties("mcp.authorization")
public record McpAuthorizationProperties(String resourceUrl, ClientType clientType) {

	public McpAuthorizationProperties {
		clientType = (clientType == null) ? ClientType.CHATGPT : clientType;
	}
}
