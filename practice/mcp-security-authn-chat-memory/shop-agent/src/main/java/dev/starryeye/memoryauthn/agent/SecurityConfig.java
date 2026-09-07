package dev.starryeye.memoryauthn.agent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.Supplier;

@Configuration
public class SecurityConfig {

    /**
     * {@code DELETE /api/conversations/{label}} 은 상태를 바꾸므로 CSRF 예외 목록에
     * 넣지 않는다 — 이 practice 의 주제가 보안 경계인데 파괴적인 엔드포인트를 예외로
     * 두면 잘못된 교훈을 준다. 부모 practice 들이 예외로 둔 {@code /api/chat} 도 여기서는
     * 예외를 두지 않는다: 쿠키 기반 CSRF 로 전환해 index.html 이 직접 토큰을 실어 보낸다.
     *
     * <p>{@code CookieCsrfTokenRepository.withHttpOnlyFalse()} 는 JS 가 읽을 수 있는
     * {@code XSRF-TOKEN} 쿠키를 발급한다. 다만 토큰은 지연 로딩(BREACH 방지)이라 실제로
     * 어딘가에서 "읽혀야" 쿠키가 응답에 실린다 — index.html 은 서버가 렌더링하는 페이지가
     * 아니므로 아무도 그 시점에 읽어주지 않는다. {@link CsrfCookieFilter} 가 매 요청마다
     * 강제로 읽어 로딩을 트리거한다(Spring Security 공식 SPA 가이드와 동일한 패턴).
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults())
                .oauth2Client(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .build();
    }

    /**
     * 헤더로 온 원본(비-XOR) 토큰은 그대로 비교하고, 그 외(예: 폼 hidden input)는
     * 기존처럼 XOR 마스킹을 해제한다. Spring Security 레퍼런스 문서의 SPA 가이드 그대로다.
     */
    private static final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

        private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            this.delegate.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String headerValue = request.getHeader(csrfToken.getHeaderName());
            return StringUtils.hasText(headerValue)
                    ? super.resolveCsrfTokenValue(request, csrfToken)
                    : this.delegate.resolveCsrfTokenValue(request, csrfToken);
        }
    }

    /** 매 요청마다 {@code CsrfToken} 을 실제로 읽어 지연 로딩을 강제하고, 그 결과로 쿠키가 발급되게 한다. */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
