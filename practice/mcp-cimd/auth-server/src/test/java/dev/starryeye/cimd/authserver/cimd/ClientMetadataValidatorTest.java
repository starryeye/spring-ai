package dev.starryeye.cimd.authserver.cimd;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ClientMetadataValidatorTest {

	static final URI CHATGPT = URI.create("https://localhost:8172/oauth/client.json");

	static final URI CLAUDE = URI.create("https://localhost:8172/oauth/public-client.json");

	static final String REDIRECT_URI = "http://localhost:8170/login/oauth2/code/authserver";

	ClientMetadataValidator validator = new ClientMetadataValidator(new ClientIdUrlValidator(
			"https://localhost:8172", host -> new InetAddress[] { InetAddress.getByName("93.184.216.34") }));

	static Map<String, Object> chatgpt() {
		Map<String, Object> document = new HashMap<>();
		document.put("client_id", CHATGPT.toString());
		document.put("client_name", "Shop Agent (ChatGPT형)");
		document.put("redirect_uris", List.of(REDIRECT_URI));
		document.put("grant_types", List.of("authorization_code", "refresh_token"));
		document.put("token_endpoint_auth_method", "private_key_jwt");
		document.put("token_endpoint_auth_signing_alg", "RS256");
		document.put("jwks_uri", "https://localhost:8172/oauth/jwks.json");
		// ChatGPT 문서에 실제로 있는 확장 field다. 표준 field가 아니므로 무시한다.
		document.put("token_endpoint_auth_methods_supported", List.of("none", "private_key_jwt"));
		return document;
	}

	static Map<String, Object> claude() {
		Map<String, Object> document = new HashMap<>();
		document.put("client_id", CLAUDE.toString());
		document.put("client_name", "Shop Agent (Claude형)");
		document.put("redirect_uris", List.of(REDIRECT_URI));
		document.put("grant_types", List.of("authorization_code", "refresh_token"));
		document.put("token_endpoint_auth_method", "none");
		return document;
	}

	@Test
	void ChatGPT형_문서를_읽는다() {
		ClientMetadata metadata = this.validator.validate(CHATGPT, chatgpt());

		assertThat(metadata.clientId()).isEqualTo(CHATGPT.toString());
		assertThat(metadata.clientName()).isEqualTo("Shop Agent (ChatGPT형)");
		assertThat(metadata.redirectUris()).containsExactly(REDIRECT_URI);
		assertThat(metadata.authenticationMethod()).isEqualTo(ClientAuthenticationMethod.PRIVATE_KEY_JWT);
		assertThat(metadata.jwksUri()).isEqualTo("https://localhost:8172/oauth/jwks.json");
		assertThat(metadata.grantTypes())
				.containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
	}

	@Test
	void Claude형_문서를_읽는다() {
		ClientMetadata metadata = this.validator.validate(CLAUDE, claude());

		assertThat(metadata.authenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
		assertThat(metadata.jwksUri()).isNull();
	}

	@Test
	void grant_types가_없으면_authorization_code만이다() {
		Map<String, Object> document = claude();
		document.remove("grant_types");

		assertThat(this.validator.validate(CLAUDE, document).grantTypes())
				.containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
	}

	@Test
	void client_id가_문서_주소와_다르면_거절한다() {
		Map<String, Object> document = claude();
		document.put("client_id", "https://localhost:8172/oauth/other.json");
		거절(CLAUDE, document, "client_id");
	}

	@Test
	void 필수_field가_없으면_거절한다() {
		for (String field : List.of("client_id", "client_name", "redirect_uris", "token_endpoint_auth_method")) {
			Map<String, Object> document = claude();
			document.remove(field);
			거절(CLAUDE, document, field);
		}
	}

	@Test
	void redirect_uris가_비었거나_절대_주소가_아니면_거절한다() {
		Map<String, Object> empty = claude();
		empty.put("redirect_uris", List.of());
		거절(CLAUDE, empty, "redirect_uris");

		Map<String, Object> relative = claude();
		relative.put("redirect_uris", List.of("/callback"));
		거절(CLAUDE, relative, "redirect_uris");
	}

	@Test
	void 비밀이_있으면_거절한다() {
		Map<String, Object> secret = claude();
		secret.put("client_secret", "s");
		거절(CLAUDE, secret, "client_secret");

		Map<String, Object> expires = claude();
		expires.put("client_secret_expires_at", 0);
		거절(CLAUDE, expires, "client_secret_expires_at");
	}

	@Test
	void none과_private_key_jwt_밖의_인증_방식은_거절한다() {
		Map<String, Object> document = claude();
		document.put("token_endpoint_auth_method", "client_secret_basic");
		거절(CLAUDE, document, "token_endpoint_auth_method");
	}

	@Test
	void private_key_jwt인데_jwks_uri가_없거나_https가_아니면_거절한다() {
		Map<String, Object> missing = chatgpt();
		missing.remove("jwks_uri");
		거절(CHATGPT, missing, "jwks_uri");

		Map<String, Object> http = chatgpt();
		http.put("jwks_uri", "http://localhost:8172/oauth/jwks.json");
		거절(CHATGPT, http, "https");
	}

	@Test
	void 서명_알고리즘은_RS256만_받는다() {
		Map<String, Object> document = chatgpt();
		document.put("token_endpoint_auth_signing_alg", "HS256");
		거절(CHATGPT, document, "RS256");
	}

	@Test
	void grant_types에_authorization_code가_없으면_거절한다() {
		Map<String, Object> document = claude();
		document.put("grant_types", List.of("client_credentials"));
		거절(CLAUDE, document, "authorization_code");
	}

	void 거절(URI url, Map<String, Object> document, String reason) {
		assertThatExceptionOfType(InvalidClientMetadataException.class)
				.isThrownBy(() -> this.validator.validate(url, document))
				.withMessageContaining(reason);
	}
}
