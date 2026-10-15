package dev.starryeye.cimd.authserver.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * public client의 consent는 기록하지 않는다(OAuth 2.1 §7.3.1).
 *
 * <p>Authorization Server가 client의 신원을 확인할 수 없다면, 사용자가 같은 client_id에 전에
 * consent했더라도 그 요청을 처음처럼 처리하는 것이 좋다(SHOULD).
 * consent 화면 없이 자동으로 처리하지 않는다(SHOULD NOT).
 * 비밀이 없는 public client({@code none})는 누구나 그 client_id를 댈 수 있으므로 신원을 확인할 수 없다.
 *
 * <p>Spring은 한 번 받은 consent를 이 서비스에 저장한다.
 * 다음 authorization request에서 저장된 consent가 요청한 scope를 모두 덮으면 consent 화면을 건너뛴다.
 * public client라면 consent를 저장하지 않고, 조회에도 {@code null}을 돌려준다.
 * consent를 받은 그 요청은 저장 여부와 상관없이 방금 고른 scope로 code를 발급한다.
 * Spring이 저장과 상관없이 consent를 건너뛰는 경우(scope가 {@code openid} 하나)는
 * {@link ConsentableScopeValidator}가 막는다.
 *
 * <p>confidential client의 consent는 감싼 서비스에 맡기되, 그때 문서의 {@code redirect_uris}로 만든 hash를 함께 저장한다.
 * 사용자가 허락한 것은 "그 주소들로 돌아가는 client"다. 문서 host를 손에 넣은 쪽이 redirect 주소를 바꾸면,
 * 저장된 consent만으로 consent 화면 없이 새 주소로 code가 나간다. 그래서 조회할 때 지금 문서의 hash와 다르면
 * 저장된 consent가 없는 것처럼 {@code null}을 돌려주어 consent를 다시 묻는다. hash가 없는 consent도 쓰지 않는다.
 * hash는 {@code SCOPE_}로 시작하지 않는 authority라서, consent 화면과 Spring이 보는 {@code getScopes()}에는 섞이지 않는다.
 */
public class PublicClientConsentService implements OAuth2AuthorizationConsentService {

	/** consent에 함께 저장하는 문서 redirect 주소 목록 hash의 authority 앞부분이다. 뒤에 SHA-256 hex가 붙는다. */
	public static final String REDIRECT_URIS_AUTHORITY_PREFIX = "REDIRECT_URIS_SHA256_";

	private final OAuth2AuthorizationConsentService delegate;

	private final RegisteredClientRepository registeredClientRepository;

	public PublicClientConsentService(OAuth2AuthorizationConsentService delegate,
			RegisteredClientRepository registeredClientRepository) {
		this.delegate = delegate;
		this.registeredClientRepository = registeredClientRepository;
	}

	@Override
	public void save(OAuth2AuthorizationConsent authorizationConsent) {
		RegisteredClient client = this.registeredClientRepository.findById(authorizationConsent.getRegisteredClientId());
		if (isPublicClient(client)) {
			return;
		}
		GrantedAuthority redirectUris = redirectUrisAuthority(client);
		this.delegate.save(OAuth2AuthorizationConsent.from(authorizationConsent)
				.authorities(authorities -> {
					authorities.removeIf(PublicClientConsentService::isRedirectUrisAuthority);
					authorities.add(redirectUris);
				})
				.build());
	}

	@Override
	public void remove(OAuth2AuthorizationConsent authorizationConsent) {
		this.delegate.remove(authorizationConsent);
	}

	@Override
	public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
		RegisteredClient client = this.registeredClientRepository.findById(registeredClientId);
		if (isPublicClient(client)) {
			return null;
		}
		OAuth2AuthorizationConsent consent = this.delegate.findById(registeredClientId, principalName);
		if (consent == null || !consent.getAuthorities().contains(redirectUrisAuthority(client))) {
			return null;
		}
		return consent;
	}

	/**
	 * client를 찾지 못하면 public client로 본다.
	 * CIMD 저장소는 문서를 가져오지 못하면 {@code null}을 돌려준다.
	 * cache하지 않은 문서는 조회할 때마다 다시 가져오므로, consent를 저장하거나 찾는 순간에 실패할 수 있다.
	 * 그때 confidential client로 보면 신원을 확인하지 못한 client의 consent를 저장하거나 꺼내 쓰게 된다.
	 */
	private static boolean isPublicClient(RegisteredClient client) {
		return client == null || client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE);
	}

	private static boolean isRedirectUrisAuthority(GrantedAuthority authority) {
		return authority.getAuthority() != null && authority.getAuthority().startsWith(REDIRECT_URIS_AUTHORITY_PREFIX);
	}

	/** 주소 목록을 정렬해 줄바꿈으로 이은 문자열의 SHA-256이다. 순서만 바뀐 문서는 같은 값이 된다. */
	private static GrantedAuthority redirectUrisAuthority(RegisteredClient client) {
		String joined = String.join("\n", client.getRedirectUris().stream().sorted().toList());
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
			return new SimpleGrantedAuthority(REDIRECT_URIS_AUTHORITY_PREFIX + HexFormat.of().formatHex(digest));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256을 쓸 수 없다", ex);
		}
	}
}
