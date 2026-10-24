package dev.starryeye.official.agent.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * login 실패를 401 응답 본문으로 알린다.
 *
 * <p>기본 동작은 login 페이지로 되돌리는 것이다.
 * 그런데 이 앱의 login 페이지는 곧 authorization request라서,
 * 실패할 때마다 다시 Authorization Server로 가는 고리가 된다.
 * 그래서 실패를 알리고 멈춘다.
 *
 * <p>예외 메시지에는 callback 요청에 누군가 넣은 값(예: {@code iss mismatch}의 {@code iss})이 들어 있을 수 있다.
 * 그래서 응답 본문에는 고정 문장과 OAuth 오류 코드만 쓴다.
 * 오류 코드도 callback의 {@code error} parameter에서 올 수 있으므로 OAuth 오류 코드 모양일 때만 쓴다.
 * 자세한 이유는 로그에 남기되, 줄바꿈 같은 제어 문자를 바꾸고 길이를 잘라 가짜 로그 줄을 끼워 넣지 못하게 한다.
 */
public class LoginFailureHandler implements AuthenticationFailureHandler {

	private static final Logger log = LoggerFactory.getLogger(LoginFailureHandler.class);

	/** 응답 본문에 쓸 수 있는 OAuth 오류 코드 모양이다(예: {@code invalid_request}, {@code access_denied}). */
	private static final Pattern ERROR_CODE = Pattern.compile("[a-z0-9_]{1,64}");

	private static final int MAX_LOG_VALUE_LENGTH = 200;

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		log.warn("로그인 실패: {}", forLog(exception.getMessage()));
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("text/plain;charset=UTF-8");
		response.getWriter().write(body(exception));
	}

	/** 고정 문장과 OAuth 오류 코드만 담은 본문이다. */
	static String body(AuthenticationException exception) {
		String code = (exception instanceof OAuth2AuthenticationException oauth2)
				? oauth2.getError().getErrorCode() : null;
		String first = (code != null && ERROR_CODE.matcher(code).matches()) ? "로그인 실패: " + code : "로그인 실패";
		return first + "\n자세한 이유는 agent 로그에 남겼다.\n";
	}

	/**
	 * 로그에 남길 값에서 줄바꿈 같은 제어 문자를 {@code ?}로 바꾸고 길이를 줄인다.
	 * 그대로 쓰면 callback에 넣은 값으로 로그에 가짜 줄을 끼워 넣을 수 있다.
	 */
	static String forLog(String value) {
		if (value == null) {
			return "null";
		}
		int length = Math.min(value.length(), MAX_LOG_VALUE_LENGTH);
		if (length < value.length() && Character.isHighSurrogate(value.charAt(length - 1))) {
			length--;
		}
		StringBuilder safe = new StringBuilder(length + 16);
		for (int i = 0; i < length; i++) {
			char c = value.charAt(i);
			int type = Character.getType(c);
			boolean control = Character.isISOControl(c) || type == Character.LINE_SEPARATOR
					|| type == Character.PARAGRAPH_SEPARATOR;
			safe.append(control ? '?' : c);
		}
		if (length < value.length()) {
			safe.append("...(").append(value.length() - length).append("자 생략)");
		}
		return safe.toString();
	}
}
