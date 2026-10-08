package dev.starryeye.cimd.authserver.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CIMD client의 consent 화면이다.
 *
 * <p>CIMD client는 미리 맺은 관계가 없고, 이름(client_name)은 client가 문서에 마음대로 적는다.
 * 그래서 이름만 보여 주면 진짜 client의 이름을 단 공격자의 요청을 사용자가 구별하지 못한다.
 * 이 화면은 이름과 함께 문서 host(client_id의 host)와 허락한 뒤 돌아갈 redirect host를 보여 준다.
 * client의 redirect 주소가 모두 loopback이면, 같은 기기의 다른 프로그램도 그 client 행세를 할 수 있으므로 경고한다
 * (MCP Authorization — Localhost Redirect URI Risks, CIMD draft §6.4).
 *
 * <p>form은 Spring 기본 consent 화면과 같은 field({@code client_id}, {@code state}, {@code scope})로 보낸다.
 * {@code openid}는 consent 대상이 아니므로 체크박스를 두지 않는다. 고른 scope가 있으면 Spring이 다시 붙인다.
 */
@RestController
public class ConsentController {

	public static final String PATH = "/oauth2/consent";

	private static final OAuth2TokenType STATE = new OAuth2TokenType(OAuth2ParameterNames.STATE);

	/** 각 자리가 0~255이고 앞에 0이 붙지 않은 IPv4 주소다. 이런 문자열만 {@link InetAddress}에 넘겨 이름 조회를 막는다. */
	private static final Pattern IPV4_LITERAL = Pattern
			.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

	private final RegisteredClientRepository clients;

	private final OAuth2AuthorizationConsentService consents;

	private final OAuth2AuthorizationService authorizations;

	public ConsentController(RegisteredClientRepository clients, OAuth2AuthorizationConsentService consents,
			OAuth2AuthorizationService authorizations) {
		this.clients = clients;
		this.consents = consents;
		this.authorizations = authorizations;
	}

	@GetMapping(value = PATH, produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
	public String consent(Principal principal, @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
			@RequestParam(OAuth2ParameterNames.SCOPE) String scope, @RequestParam(OAuth2ParameterNames.STATE) String state) {
		RegisteredClient client = this.clients.findByClientId(clientId);
		if (client == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "모르는 client다");
		}
		OAuth2AuthorizationConsent previous = this.consents.findById(client.getId(), principal.getName());
		Set<String> approved = (previous != null) ? previous.getScopes() : Set.of();
		List<String> requested = Arrays.stream(scope.split(" "))
				.filter(value -> !value.isBlank() && !OidcScopes.OPENID.equals(value))
				.toList();
		List<String> toApprove = requested.stream().filter(value -> !approved.contains(value)).toList();
		List<String> alreadyApproved = requested.stream().filter(approved::contains).toList();
		return page(client, state, redirectUri(client, state), toApprove, alreadyApproved);
	}

	/**
	 * state는 이 client가 시작한 authorization의 것일 때만 믿는다.
	 * 다른 client의 state를 붙여 오면, 그 client의 redirect host를 이 client의 화면에 보여 주게 되기 때문이다.
	 */
	private String redirectUri(RegisteredClient client, String state) {
		OAuth2Authorization authorization = this.authorizations.findByToken(state, STATE);
		if (authorization == null || !client.getId().equals(authorization.getRegisteredClientId())) {
			return null;
		}
		OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
		return (request != null) ? request.getRedirectUri() : null;
	}

	private static String page(RegisteredClient client, String state, String redirectUri, List<String> toApprove,
			List<String> alreadyApproved) {
		StringBuilder html = new StringBuilder();
		html.append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><title>권한 허락</title></head><body>");
		html.append("<h1>권한 요청: ").append(escape(client.getClientName())).append("</h1>");
		html.append("<p>client 문서: <code>").append(escape(host(client.getClientId()))).append("</code></p>");
		if (redirectUri != null) {
			html.append("<p>허락하면 돌아갈 주소: <code>").append(escape(host(redirectUri))).append("</code></p>");
		}
		if (onlyLoopback(client.getRedirectUris())) {
			html.append("<p><strong>이 client는 이 기기의 주소(localhost)로만 돌아갑니다. ")
					.append("같은 기기의 다른 프로그램도 이 client의 이름을 댈 수 있으니, 직접 시작한 요청인지 확인하세요.</strong></p>");
		}
		html.append("<form method=\"post\" action=\"/oauth2/authorize\">");
		hidden(html, client.getClientId(), state);
		for (String scope : toApprove) {
			html.append("<p><label><input type=\"checkbox\" name=\"scope\" value=\"").append(escape(scope))
					.append("\" id=\"").append(escape(scope)).append("\"> ").append(escape(scope)).append("</label></p>");
		}
		if (!alreadyApproved.isEmpty()) {
			html.append("<p>이미 허락한 권한</p>");
			for (String scope : alreadyApproved) {
				html.append("<p><label><input type=\"checkbox\" checked disabled> ").append(escape(scope))
						.append("</label></p>");
			}
		}
		html.append("<p><button type=\"submit\">권한 허락</button></p></form>");
		// 아무 scope도 고르지 않고 보낸다. public client면 access_denied로 끝나고,
		// 이미 허락한 scope가 있는 client면 그 scope만으로 code를 받는다(Spring 기본 화면의 Cancel과 같다).
		html.append("<form method=\"post\" action=\"/oauth2/authorize\">");
		hidden(html, client.getClientId(), state);
		html.append("<p><button type=\"submit\">거절</button></p></form>");
		html.append("</body></html>");
		return html.toString();
	}

	private static void hidden(StringBuilder html, String clientId, String state) {
		html.append("<input type=\"hidden\" name=\"client_id\" value=\"").append(escape(clientId)).append("\">");
		html.append("<input type=\"hidden\" name=\"state\" value=\"").append(escape(state)).append("\">");
	}

	/**
	 * 등록한 redirect 주소가 모두 같은 기기로 돌아오는지 본다.
	 * 이름처럼 보이기만 하는 host(예: {@code 127.evil.example})를 loopback으로 잘못 알리면
	 * 사용자가 실제로는 다른 곳으로 돌아가는 client를 "이 기기만" 쓴다고 믿게 되므로, 이름은 정확히 가려낸다.
	 */
	static boolean onlyLoopback(Collection<String> redirectUris) {
		return !redirectUris.isEmpty() && redirectUris.stream().allMatch(ConsentController::loopback);
	}

	private static boolean loopback(String uri) {
		String host = URI.create(uri).getHost();
		if (host == null) {
			return false;
		}
		host = host.toLowerCase(Locale.ROOT);
		if (host.startsWith("[") && host.endsWith("]")) {
			return loopbackAddress(host.substring(1, host.length() - 1));
		}
		if (IPV4_LITERAL.matcher(host).matches()) {
			return loopbackAddress(host);
		}
		// "localhost."처럼 끝에 점이 붙어도 같은 이름이다.
		String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
		return "localhost".equals(name) || name.endsWith(".localhost");
	}

	/**
	 * IP 주소 문자열(IPv4 또는 대괄호를 뗀 IPv6)만 받는다.
	 * 이런 문자열은 {@link InetAddress#getByName}이 이름 조회(DNS) 없이 바로 해석한다.
	 * {@code ::1}, {@code 0:0:0:0:0:0:0:1}, {@code ::ffff:127.0.0.1}처럼 표기가 달라도 같은 주소로 본다.
	 */
	private static boolean loopbackAddress(String literal) {
		try {
			return InetAddress.getByName(literal).isLoopbackAddress();
		}
		catch (UnknownHostException ex) {
			return false;
		}
	}

	/**
	 * 사용자가 믿고 볼 host만 보여 준다.
	 * {@code user@host} 꼴의 userinfo를 그대로 보여 주면 {@code good.example@evil.example}에서 진짜 host가 가려지므로,
	 * URI가 가려낸 host와 port만 보여 준다.
	 * 영문·숫자가 아닌 글자가 섞인 host는 {@code getHost()}가 null이라, userinfo를 떼고 punycode로 바꿔 보여 준다.
	 * 키릴 문자 а와 라틴 문자 a처럼 눈으로 구별되지 않는 글자가 있어도 {@code xn--} 꼴로는 구별된다.
	 * authority가 없는 주소(예: {@code a:b})는 host를 가려낼 수 없으므로 주소 전체를 보여 준다.
	 */
	private static String host(String uri) {
		URI parsed = URI.create(uri);
		String host = parsed.getHost();
		if (host != null) {
			return (parsed.getPort() != -1) ? host + ":" + parsed.getPort() : host;
		}
		String authority = parsed.getRawAuthority();
		if (authority == null) {
			return uri;
		}
		String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
		if (hostAndPort.chars().allMatch(ch -> ch < 128)) {
			return hostAndPort;
		}
		try {
			return IDN.toASCII(hostAndPort, IDN.ALLOW_UNASSIGNED);
		}
		catch (IllegalArgumentException ex) {
			return uri;
		}
	}

	private static String escape(String value) {
		return HtmlUtils.htmlEscape(value);
	}
}
