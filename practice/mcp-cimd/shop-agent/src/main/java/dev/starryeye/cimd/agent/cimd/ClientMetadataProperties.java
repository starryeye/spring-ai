package dev.starryeye.cimd.agent.cimd;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent가 client 문서를 올리는 곳과 서명 key의 위치다.
 *
 * @param baseUrl 문서 주소의 앞부분. client_id가 {@code https}이고 path가 있어야 하므로 {@code https}다
 * @param port 문서 서버가 여는 포트. 테스트는 0으로 OS가 고르게 한다
 * @param tlsBundle 문서 서버의 인증서가 든 Spring Boot SSL bundle 이름
 * @param signingKeyStore {@code private_key_jwt}에 쓰는 RSA key가 든 PKCS12 파일의 주소({@code file:}, {@code classpath:})
 * @param signingKeyStorePassword 위 파일의 비밀번호
 * @param signingKeyAlias 위 파일 안의 key 이름
 * @param redirectUri 문서의 {@code redirect_uris}에 올리고 authorization request에도 쓰는 주소
 */
@ConfigurationProperties("mcp.client-metadata")
public record ClientMetadataProperties(String baseUrl, Integer port, String tlsBundle, String signingKeyStore,
		String signingKeyStorePassword, String signingKeyAlias, String redirectUri) {

	public ClientMetadataProperties {
		baseUrl = (baseUrl == null) ? "https://localhost:8172" : baseUrl;
		port = (port == null) ? 8172 : port;
		tlsBundle = (tlsBundle == null) ? "client-metadata-tls" : tlsBundle;
		signingKeyStorePassword = (signingKeyStorePassword == null) ? "changeit" : signingKeyStorePassword;
		signingKeyAlias = (signingKeyAlias == null) ? "client-signing" : signingKeyAlias;
		redirectUri = (redirectUri == null) ? "http://localhost:8170/login/oauth2/code/authserver" : redirectUri;
	}
}
