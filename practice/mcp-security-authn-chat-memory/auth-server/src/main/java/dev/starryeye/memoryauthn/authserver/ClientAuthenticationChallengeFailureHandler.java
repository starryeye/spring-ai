package dev.starryeye.memoryauthn.authserver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 클라이언트 인증 실패 시 {@code WWW-Authenticate} 를 채운다(RFC 6749 §5.2, OAuth 2.1 §3.2.4).
 *
 * <p>"클라이언트가 {@code Authorization} 요청 헤더로 인증을 시도했다면, 인가 서버는 반드시
 * HTTP 401 과 함께 그 인증 스킴에 맞는 {@code WWW-Authenticate} 응답 헤더를 실어야 한다."
 * Spring 인가 서버의 {@code OAuth2ClientAuthenticationFilter#onAuthenticationFailure} 는
 * 이 요구사항을 TODO 주석으로만 남겨 두고 실제로는 헤더를 붙이지 않는다
 * (spring-security 이슈 #18285, 미해결. 7.2.0-M1 에서도 동일).
 *
 * <p>본문과 상태 코드는 Spring 기본 동작(오류 코드만 담은 JSON, {@code invalid_client} 면
 * 401 그 외엔 400)을 그대로 재현한다. {@code Authorization} 헤더 없이 인증을 시도한 경우
 * (예: {@code client_secret_post} 로 폼 파라미터만 보낸 경우)에는 스킴을 알 수 없으므로
 * 헤더를 붙이지 않는다 — RFC 요구가 "Authorization 헤더로 시도한 경우"에 한정되기 때문이다.
 *
 * <p>이 클래스는 practice 고유 값을 담지 않는다. 다른 practice 로 패키지만 바꿔 재사용한다.
 */
public class ClientAuthenticationChallengeFailureHandler implements AuthenticationFailureHandler {

	// RFC 7230 §3.2.6 token 문법. 스킴 토큰에 공백·따옴표·제어문자가 섞여 헤더로 그대로
	// 주입되는 것을 막는다 — 문법에 맞지 않으면 알 수 없는 스킴으로 보고 기본값을 쓴다.
	private static final Pattern TOKEN = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$");

	private static final String DEFAULT_SCHEME = "Basic";

	private final HttpMessageConverter<OAuth2Error> errorHttpResponseConverter = new OAuth2ErrorHttpMessageConverter();

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		OAuth2Error error = ((OAuth2AuthenticationException) exception).getError();

		String scheme = requestedScheme(request);
		if (scheme != null) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(scheme));
		}

		ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);
		httpResponse.setStatusCode(OAuth2ErrorCodes.INVALID_CLIENT.equals(error.getErrorCode())
				? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST);
		// 과한 정보를 노출하지 않으려고 오류 코드만 돌려주는 Spring 기본 동작을 그대로 따른다.
		this.errorHttpResponseConverter.write(new OAuth2Error(error.getErrorCode()), null, httpResponse);
	}

	/**
	 * 클라이언트가 {@code Authorization} 헤더로 인증을 시도했다면 그 스킴 토큰을,
	 * 아니라면 {@code null} 을 반환한다.
	 */
	private static String requestedScheme(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (!StringUtils.hasText(header)) {
			return null;
		}
		String token = header.trim().split("\\s+", 2)[0];
		return TOKEN.matcher(token).matches() ? token : DEFAULT_SCHEME;
	}

	/**
	 * 스킴만 있는 챌린지("Basic")에 issuer 를 realm 으로 덧붙인다. issuer 를 구하지 못하면
	 * realm 없이 스킴만 돌려준다 — RFC 9110 §11.6.1 상 realm 은 challenge 의 필수 파라미터가
	 * 아니라 스킴만으로도 유효한 챌린지이기 때문이다. 이 클래스는 다른 practice 로 그대로
	 * 옮겨질 것을 전제로 하므로, issuer 가 정적으로 보장되지 않는 환경에서도 500 으로
	 * 퇴행하지 않게 한다.
	 */
	private static String challenge(String scheme) {
		String issuer = issuer();
		if (!StringUtils.hasText(issuer)) {
			return scheme;
		}
		// RFC 9110 §11.6.1 문법상 realm 은 quoted-string 이어야 한다.
		return scheme + " realm=\"" + quoted(issuer) + "\"";
	}

	private static String issuer() {
		return AuthorizationServerContextHolder.getContext().getIssuer();
	}

	private static String quoted(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
