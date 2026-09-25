package dev.starryeye.authserver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * token 요청에 {@code resource} 가 여러 개면 {@code invalid_target} 으로 거부한다(RFC 8707 §2).
 *
 * <p>Spring 의 token 요청 converter 는 값이 여러 개인 파라미터를 {@code String[]} 로 넘긴다. 모듈의
 * {@code ResourceIdentifierAudienceTokenCustomizer} 는 그 값을 {@code (String)} 으로 캐스트하므로 500 이 된다.
 * 이 converter 는 기본 converter 들보다 먼저 돌아 그 전에 막고, 나머지 요청은 {@code null} 을 돌려
 * 다음 converter 에 넘긴다.
 */
public class SingleResourceTokenRequestConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        String[] resources = request.getParameterValues(McpResourceProperties.RESOURCE_PARAMETER);
        if (resources != null && resources.length > 1) {
            throw new OAuth2AuthenticationException(new OAuth2Error(McpResourceProperties.INVALID_TARGET,
                    "Only one resource is supported per token request", McpResourceProperties.RFC_8707));
        }
        return null;
    }
}
