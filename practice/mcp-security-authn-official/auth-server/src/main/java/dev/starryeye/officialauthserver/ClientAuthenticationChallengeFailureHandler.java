package dev.starryeye.officialauthserver;

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
 * client 인증이 실패하면 {@code WWW-Authenticate}를 채운다(RFC 6749 §5.2, OAuth 2.1 §3.2.4).
 *
 * <p>RFC 6749 §5.2는 이렇게 정한다.
 * client가 {@code Authorization} 요청 header로 인증을 시도했다면, Authorization Server는
 * HTTP 401과 함께 그 인증 scheme에 맞는 {@code WWW-Authenticate} 응답 header를 반드시 넣는다.
 * Spring Authorization Server의 {@code OAuth2ClientAuthenticationFilter#onAuthenticationFailure}는
 * 이 요구를 TODO 주석으로만 남기고, 실제로는 header를 붙이지 않는다
 * (spring-security 이슈 #18285, 미해결. 7.2.0-M1에서도 같다).
 *
 * <p>본문과 상태 코드는 Spring 기본 동작을 그대로 따른다.
 * 본문은 오류 코드만 담은 JSON이고, 상태 코드는 {@code invalid_client}면 401, 그 밖에는 400이다.
 * {@code Authorization} header 없이 인증을 시도했다면 scheme을 알 수 없으므로 header를 붙이지 않는다.
 * {@code client_secret_post}로 form parameter만 보낸 경우가 그렇다.
 * RFC의 요구도 "Authorization header로 시도한 경우"에만 해당한다.
 *
 * <p>이 handler는 {@code OAuth2ClientAuthenticationFilter}에만 연결되어 있다.
 * 하지만 그 filter를 거치는 실패가 모두 {@code invalid_client}인 것은 아니다.
 * Spring의 {@code ClientSecretAuthenticationProvider}와 {@code PublicClientAuthenticationProvider}는
 * 내부의 {@code CodeVerifierAuthenticator}로 PKCE {@code code_verifier}를 검증한다.
 * 이 검증은 "client 인증"의 일부로 이 filter 안에서 일어나고, 실패하면 {@code invalid_grant}를 던진다
 * ({@code CodeVerifierAuthenticator#invalidGrantException}).
 * 그래서 code_verifier 불일치도 이 handler를 거치지만, 오류 코드는 invalid_grant다.
 * RFC 6749 §5.2의 challenge 의무는 {@code invalid_client} 응답에만 해당하므로, 오류 코드로 한 번 더 거른다.
 * 거르지 않으면 client 인증은 성공한 요청에도 Basic challenge가 붙는다.
 * code_verifier만 틀린 token request가 그 예다.
 * 그러면 client는 "credentials를 다시 보내라"는 잘못된 신호를 받는다.
 *
 * <p>이 클래스에는 practice 고유의 값이 없다. 다른 practice에서도 package만 바꿔 그대로 쓸 수 있다.
 */
public class ClientAuthenticationChallengeFailureHandler implements AuthenticationFailureHandler {

	// RFC 7230 §3.2.6의 token 문법이다. scheme token에 공백·따옴표·제어 문자가 섞여 header에 그대로
	// 들어가는 것을 막는다. 문법에 맞지 않으면 알 수 없는 scheme으로 보고 기본값을 쓴다.
	private static final Pattern TOKEN = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$");

	private static final String DEFAULT_SCHEME = "Basic";

	private final HttpMessageConverter<OAuth2Error> errorHttpResponseConverter = new OAuth2ErrorHttpMessageConverter();

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		OAuth2Error error = ((OAuth2AuthenticationException) exception).getError();
		boolean invalidClient = OAuth2ErrorCodes.INVALID_CLIENT.equals(error.getErrorCode());

		// invalid_client가 아니면(예: PKCE code_verifier 불일치로 생긴 invalid_grant) 이 filter를
		// 거쳐 왔더라도 RFC 6749 §5.2가 말하는 "client 인증 실패"가 아니므로 header를 붙이지 않는다.
		if (invalidClient) {
			String scheme = requestedScheme(request);
			if (scheme != null) {
				response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(scheme));
			}
		}

		ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);
		httpResponse.setStatusCode(invalidClient ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST);
		// 과한 정보를 노출하지 않으려고 오류 코드만 돌려주는 Spring 기본 동작을 그대로 따른다.
		this.errorHttpResponseConverter.write(new OAuth2Error(error.getErrorCode()), null, httpResponse);
	}

	/**
	 * client가 {@code Authorization} header로 인증을 시도했다면 그 scheme token을 돌려준다.
	 * scheme이 token 문법에 맞지 않으면 기본값 Basic을 돌려준다.
	 * header로 시도하지 않았다면 {@code null}을 돌려준다.
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
	 * scheme만 있는 challenge("Basic")에 issuer를 realm으로 덧붙인다.
	 * issuer를 구하지 못하면 realm 없이 scheme만 돌려준다.
	 * RFC 9110 §11.3의 challenge 문법에서 parameter는 선택이라, scheme만 있어도 문법에는 맞는다.
	 * 다만 Basic scheme은 RFC 7617 §2가 realm을 필수로 정하므로, realm 없는 challenge는 issuer를 구하지 못했을 때만 쓰는 대비책이다.
	 * 이 클래스는 다른 practice로 그대로 옮겨 쓰는 것을 전제로 한다.
	 * 그래서 issuer가 늘 있다고 보장할 수 없는 환경에서도 500으로 실패하지 않게 한다.
	 */
	private static String challenge(String scheme) {
		String issuer = issuer();
		if (!StringUtils.hasText(issuer)) {
			return scheme;
		}
		// RFC 9110 §11.5에 따라 realm 값은 quoted-string으로만 보낸다.
		return scheme + " realm=\"" + quoted(issuer) + "\"";
	}

	private static String issuer() {
		return AuthorizationServerContextHolder.getContext().getIssuer();
	}

	private static String quoted(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
