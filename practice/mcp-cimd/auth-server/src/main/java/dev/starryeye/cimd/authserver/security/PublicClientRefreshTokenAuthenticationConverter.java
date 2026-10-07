package dev.starryeye.cimd.authserver.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * public client의 refresh 요청을 client 인증 요청으로 바꾼다.
 *
 * <p>Spring의 {@code PublicClientAuthenticationConverter}는 {@code code_verifier}가 있는 요청,
 * 곧 authorization code 요청만 public client로 받는다. refresh 요청에는 {@code code_verifier}가 없다.
 * 그래서 refresh 요청에 다른 client 인증(Basic header, client_assertion, client_secret)이 없고
 * {@code client_id}가 하나뿐이면, 그 client_id의 public client 인증으로 넘긴다.
 * 만든 token에는 refresh 요청이라는 표시를 남겨, provider가 이 요청만 맡게 한다.
 *
 * <p>Spring의 client 인증 filter는 token endpoint 말고도 introspection, revocation, PAR, device authorization endpoint에서 돈다.
 * 이 converter가 그 endpoint까지 맡으면 인증 없이 {@code client_id}만으로 public client 행세를 할 수 있다.
 * 예를 들어 introspection endpoint는 token이 누구 것인지 확인하지 않으므로, 아무 token이나 읽을 수 있게 된다.
 * 그래서 token endpoint로 온 {@code POST}만 맡는다.
 */
public final class PublicClientRefreshTokenAuthenticationConverter implements AuthenticationConverter {

	private static final String DEFAULT_TOKEN_ENDPOINT = "/oauth2/token";

	@Override
	public Authentication convert(HttpServletRequest request) {
		if (!tokenEndpointRequest(request)) {
			return null;
		}
		if (!AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))) {
			return null;
		}
		if (request.getHeader(HttpHeaders.AUTHORIZATION) != null
				|| request.getParameter(OAuth2ParameterNames.CLIENT_ASSERTION) != null
				|| request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null) {
			return null;
		}
		String[] clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
		if (clientIds == null || clientIds.length != 1 || !StringUtils.hasText(clientIds[0])) {
			return null;
		}
		return new OAuth2ClientAuthenticationToken(clientIds[0], ClientAuthenticationMethod.NONE, null,
				Map.of(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.REFRESH_TOKEN.getValue()));
	}

	private static boolean tokenEndpointRequest(HttpServletRequest request) {
		return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, tokenEndpoint()).matches(request);
	}

	private static String tokenEndpoint() {
		AuthorizationServerContext context = AuthorizationServerContextHolder.getContext();
		if (context == null) {
			return DEFAULT_TOKEN_ENDPOINT;
		}
		return context.getAuthorizationServerSettings().getTokenEndpoint();
	}
}
