package dev.starryeye.cimd.authserver.security;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * public client에게도 refresh token을 만든다.
 *
 * <p>Spring의 {@code OAuth2RefreshTokenGenerator}는 public client({@code none})의 authorization code 요청이면
 * refresh token을 만들지 않는다. 새어 나간 refresh token을 누구든 쓸 수 있기 때문이다.
 * OAuth 2.1은 rotation이나 sender-constrained token을 조건으로 public client에게도 refresh token을 허용한다.
 * 이 서버는 CIMD client의 {@code reuseRefreshTokens(false)}로 rotation을 하므로, 그 client의 grant에
 * {@code refresh_token}이 있으면 만든다. Claude는 public client로 붙으면서 refresh token rotation을 요구한다.
 */
public final class PublicClientRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

	private final StringKeyGenerator keys = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

	private final Clock clock;

	public PublicClientRefreshTokenGenerator() {
		this(Clock.systemUTC());
	}

	public PublicClientRefreshTokenGenerator(Clock clock) {
		this.clock = clock;
	}

	@Override
	public OAuth2RefreshToken generate(OAuth2TokenContext context) {
		if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
			return null;
		}
		RegisteredClient client = context.getRegisteredClient();
		if (!client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
			return null;
		}
		Instant issuedAt = this.clock.instant();
		return new OAuth2RefreshToken(this.keys.generateKey(), issuedAt,
				issuedAt.plus(client.getTokenSettings().getRefreshTokenTimeToLive()));
	}
}
