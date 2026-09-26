package dev.starryeye.officialauthserver;

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
 * authorization response에 {@code iss}를 넣는다(RFC 9207).
 *
 * <p>이 {@code iss}는 mix-up 공격을 막는다.
 * client가 여러 Authorization Server를 알 때, 공격자는 자기 Authorization Server의 응답을
 * 다른 Authorization Server의 응답인 것처럼 끼워 넣을 수 있다.
 * client는 받은 {@code iss}가 요청을 보낸 Authorization Server인지 확인한 뒤에야 code를 교환한다.
 *
 * <p>성공 응답과 오류 응답 모두에 붙여야 한다(RFC 9207 §2).
 * Spring Security 7.1에는 RFC 9207 구현이 없어서 이 handler가 그 일을 맡는다.
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

		// redirect_uri를 믿을 수 없으면 redirect하지 않는다. redirect하면 open redirector가 된다.
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
