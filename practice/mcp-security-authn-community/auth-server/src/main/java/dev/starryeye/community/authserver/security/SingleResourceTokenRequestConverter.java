package dev.starryeye.community.authserver.security;

import dev.starryeye.community.authserver.config.McpResourceProperties;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * token request에 {@code resource}가 여러 개면 {@code invalid_target}으로 거부한다(RFC 8707 §2).
 *
 * <p>Spring의 token request converter는 값이 여러 개인 parameter를 {@code String[]}로 넘긴다.
 * module의 {@code ResourceIdentifierAudienceTokenCustomizer}는 그 값을 {@code (String)}으로 cast하므로 500이 된다.
 * 이 converter는 기본 converter들보다 먼저 돌아 그 전에 막는다.
 * 나머지 요청에는 {@code null}을 돌려 다음 converter에 넘긴다.
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
