package dev.starryeye.officialagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2Login(login -> login
						// 등록이 하나뿐이고, 그 등록은 발견해야 알 수 있다.
						// 로그인 화면 대신 곧바로 인가 요청으로 보낸다.
						.loginPage(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
								+ "/" + McpSecurityConfig.REGISTRATION_ID)
						.failureHandler(new LoginFailureHandler()))
				.oauth2Client(Customizer.withDefaults())
				// 학습용 단순화. index.html 의 fetch 가 CSRF 토큰을 싣지 않는다.
				.csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))
				.build();
	}
}
