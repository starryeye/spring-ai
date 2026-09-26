package dev.starryeye.officialagent;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;

/**
 * login 실패를 401 응답 본문으로 알린다.
 *
 * <p>기본 동작은 login 페이지로 되돌리는 것이다.
 * 그런데 이 앱의 login 페이지는 곧 authorization request라서,
 * 실패할 때마다 다시 Authorization Server로 가는 고리가 된다.
 * 그래서 실패 이유를 그대로 보여 주고 멈춘다.
 */
public class LoginFailureHandler implements AuthenticationFailureHandler {

	private static final Logger log = LoggerFactory.getLogger(LoginFailureHandler.class);

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		log.warn("로그인 실패: {}", exception.getMessage());
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("text/plain;charset=UTF-8");
		response.getWriter().write("로그인 실패: " + exception.getMessage());
	}
}
