package dev.starryeye.officialagent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * authorization response의 {@code iss}를 검증한다(RFC 9207 §2.4).
 *
 * <p>client는 code를 교환하기 <b>전에</b> 확인해야 한다.
 * 확인 없이 교환하면, 공격자는 자기 Authorization Server에서 받은 code를
 * 이 agent의 callback에 끼워 넣을 수 있다.
 * 그러면 agent는 그 code를 자기가 믿는 Authorization Server의 code처럼 쓰게 된다(mix-up).
 *
 * <p>Spring Security의 login filter는 {@code iss}를 읽지 않으므로 이 filter가 그 앞에 선다.
 */
public class AuthorizationResponseIssuerFilter extends OncePerRequestFilter {

    private static final String ISS = "iss";

    private static final String RFC_9207 = "https://www.rfc-editor.org/rfc/rfc9207#section-2.4";

    private final RequestMatcher callback =
            PathPatternRequestMatcher.withDefaults().matcher("/login/oauth2/code/*");

    private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequests;

    private final ClientRegistrationRepository registrations;

    private final AuthenticationFailureHandler failureHandler;

    public AuthorizationResponseIssuerFilter(
            AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequests,
            ClientRegistrationRepository registrations, AuthenticationFailureHandler failureHandler) {
        this.authorizationRequests = authorizationRequests;
        this.registrations = registrations;
        this.failureHandler = failureHandler;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!this.callback.matches(request)) {
            chain.doFilter(request, response);
            return;
        }

        OAuth2AuthorizationRequest authorizationRequest = this.authorizationRequests.loadAuthorizationRequest(request);
        if (authorizationRequest == null) {
            // 저장된 요청이 없으면 login filter가 authorization_request_not_found로 처리한다.
            chain.doFilter(request, response);
            return;
        }

        String registrationId = authorizationRequest.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
        ClientRegistration registration = (registrationId == null) ? null
                : this.registrations.findByRegistrationId(registrationId);
        if (registration == null) {
            chain.doFilter(request, response);
            return;
        }

        String problem = problem(registration, request.getParameter(ISS));
        if (problem == null) {
            chain.doFilter(request, response);
            return;
        }

        this.authorizationRequests.removeAuthorizationRequest(request, response);
        this.failureHandler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_request", problem, RFC_9207)));
    }

    private static String problem(ClientRegistration registration, String iss) {
        String expected = registration.getProviderDetails().getIssuerUri();
        boolean advertised = Boolean.TRUE.equals(registration.getProviderDetails().getConfigurationMetadata()
                .get(DiscoveredAuthorization.ISS_PARAMETER_SUPPORTED));

        if (iss == null) {
            return advertised ? "iss is missing although the authorization server advertises it" : null;
        }
        return iss.equals(expected) ? null
                : "iss mismatch: expected %s but got %s".formatted(expected, iss);
    }
}
