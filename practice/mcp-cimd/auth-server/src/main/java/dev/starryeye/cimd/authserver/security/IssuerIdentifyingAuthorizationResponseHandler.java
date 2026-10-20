package dev.starryeye.cimd.authserver.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
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
 *
 * <p>오류는 login한 사용자가 consent 화면에서 거절한 경우({@code access_denied})만 client에게 redirect로 알린다.
 * 그 밖의 오류는 redirect하지 않고 Authorization Server가 400으로 끝낸다.
 * CIMD client의 redirect URI는 누구나 자기 문서에 적는 값이라 믿을 수 없다.
 * Spring은 authorization request를 login보다 먼저 검사하므로, 오류를 그 주소로 보내면
 * login하지 않은 사람이 누른 링크도 이 서버를 거쳐 공격자 주소로 간다(open redirector).
 * RFC 9700 §4.11.2는 redirect 전에 사용자를 인증하고(MUST) 믿는 주소로만 자동 redirect하라고(SHOULD) 한다.
 * MCP Security Considerations(Open Redirection)도 믿지 못하는 주소로 보내지 않게 조치하라고 한다(MUST).
 * consent 화면의 거절은 login한 사용자가 문서 host와 redirect host를 보고 고른 결정이므로 redirect한다.
 * client는 사용자가 거절했다는 사실을 알아야 흐름을 끝낼 수 있다.
 */
public class IssuerIdentifyingAuthorizationResponseHandler
		implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

	static final String ISS = "iss";

	static final String TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8";

	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

	/** 익명 사용자(login 전 요청의 principal)는 인증된 사용자로 보지 않는다. */
	private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

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

		if (!deniedByUser(error, authorizationCodeRequest)) {
			writeError(response, error);
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

	/**
	 * login한 사용자가 consent 화면에서 거절했고, Spring이 검사한 redirect URI가 있을 때만 참이다.
	 * redirect URI가 비어 있으면 Spring이 그 주소를 믿을 수 없다고 본 오류다({@code client_id}·{@code redirect_uri} 오류).
	 */
	private boolean deniedByUser(OAuth2Error error,
			OAuth2AuthorizationCodeRequestAuthenticationToken authorizationCodeRequest) {
		return authorizationCodeRequest != null
				&& OAuth2ErrorCodes.ACCESS_DENIED.equals(error.getErrorCode())
				&& StringUtils.hasText(authorizationCodeRequest.getRedirectUri())
				&& authorizationCodeRequest.getPrincipal() instanceof Authentication principal
				&& this.trustResolver.isAuthenticated(principal);
	}

	/**
	 * 오류 코드와 설명만 text로 보인다. HTML이 아니므로 설명에 섞인 값을 escape하지 않아도 된다.
	 * {@code error_uri}와 {@code state}는 쓰지 않는다. 사용자가 읽을 화면이고, client에게 돌려주는 응답이 아니다.
	 */
	private static void writeError(HttpServletResponse response, OAuth2Error error) throws IOException {
		StringBuilder body = new StringBuilder("error=").append(error.getErrorCode()).append('\n');
		if (StringUtils.hasText(error.getDescription())) {
			body.append("error_description=").append(error.getDescription()).append('\n');
		}
		response.setStatus(HttpStatus.BAD_REQUEST.value());
		response.setContentType(TEXT_PLAIN_UTF8);
		response.getWriter().write(body.toString());
	}

	private static String issuer() {
		return AuthorizationServerContextHolder.getContext().getIssuer();
	}

	private static String encode(String value) {
		return UriUtils.encode(value, StandardCharsets.UTF_8);
	}
}
