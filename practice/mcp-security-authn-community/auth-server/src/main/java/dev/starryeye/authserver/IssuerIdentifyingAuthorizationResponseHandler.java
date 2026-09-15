package dev.starryeye.authserver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 인가 응답에 {@code iss} 를 싣는다(RFC 9207).
 *
 * <p>클라이언트가 여러 인가 서버를 알고 있을 때, 공격자가 자기 인가 서버의 응답을
 * 다른 인가 서버의 응답인 것처럼 흘려 보내는 mix-up 공격을 막는다. 클라이언트는
 * 받은 {@code iss} 가 자기가 요청을 보낸 인가 서버인지 확인한 뒤에야 코드를 교환한다.
 *
 * <p>성공 응답과 오류 응답 모두에 붙여야 한다(RFC 9207 §2.4). Spring Security 7.1 에는
 * RFC 9207 구현이 없어서 이 핸들러가 그 자리를 대신한다.
 */
public class IssuerIdentifyingAuthorizationResponseHandler
        implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    static final String ISS = "iss";

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2AuthorizationCodeRequestAuthenticationToken authorizationCodeRequest =
                (OAuth2AuthorizationCodeRequestAuthenticationToken) authentication;

        UriComponentsBuilder redirect = UriComponentsBuilder.fromUriString(authorizationCodeRequest.getRedirectUri())
                .queryParam(OAuth2ParameterNames.CODE, authorizationCodeRequest.getAuthorizationCode().getTokenValue());
        if (StringUtils.hasText(authorizationCodeRequest.getState())) {
            redirect.queryParam(OAuth2ParameterNames.STATE, encode(authorizationCodeRequest.getState()));
        }
        redirect.queryParam(ISS, encode(issuer()));

        this.redirectStrategy.sendRedirect(request, response, redirect.build(true).toUriString());
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        OAuth2Error error = ((OAuth2AuthenticationException) exception).getError();
        OAuth2AuthorizationCodeRequestAuthenticationToken authorizationCodeRequest =
                (exception instanceof OAuth2AuthorizationCodeRequestAuthenticationException codeRequestException)
                        ? codeRequestException.getAuthorizationCodeRequestAuthentication() : null;

        // redirect_uri 를 신뢰할 수 없으면 리다이렉트하지 않는다 — 오픈 리다이렉터가 된다.
        if (authorizationCodeRequest == null || !StringUtils.hasText(authorizationCodeRequest.getRedirectUri())) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), error.toString());
            return;
        }

        UriComponentsBuilder redirect = UriComponentsBuilder.fromUriString(authorizationCodeRequest.getRedirectUri())
                .queryParam(OAuth2ParameterNames.ERROR, error.getErrorCode());
        if (StringUtils.hasText(error.getDescription())) {
            redirect.queryParam(OAuth2ParameterNames.ERROR_DESCRIPTION, encode(error.getDescription()));
        }
        if (StringUtils.hasText(error.getUri())) {
            redirect.queryParam(OAuth2ParameterNames.ERROR_URI, encode(error.getUri()));
        }
        if (StringUtils.hasText(authorizationCodeRequest.getState())) {
            redirect.queryParam(OAuth2ParameterNames.STATE, encode(authorizationCodeRequest.getState()));
        }
        redirect.queryParam(ISS, encode(issuer()));

        this.redirectStrategy.sendRedirect(request, response, redirect.build(true).toUriString());
    }

    private static String issuer() {
        return AuthorizationServerContextHolder.getContext().getIssuer();
    }

    private static String encode(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8);
    }
}
