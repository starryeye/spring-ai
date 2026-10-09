package dev.starryeye.cimd.authserver;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * agent가 8172에 올리는 두 문서와 JWKS를 테스트용으로 만든다.
 * 서명 key는 테스트마다 새로 만들고, JWKS에는 public key만 넣는다.
 */
public final class TestClientDocuments {

	public static final String CHATGPT = "https://localhost:8172/oauth/client.json";

	public static final String CLAUDE = "https://localhost:8172/oauth/public-client.json";

	public static final String JWKS = "https://localhost:8172/oauth/jwks.json";

	public static final String REDIRECT_URI = "http://localhost:8170/login/oauth2/code/authserver";

	public static final RSAKey KEY = generate();

	/** kid는 같고 key만 다르다. 서명이 맞지 않아야 한다. */
	public static final RSAKey OTHER_KEY = generate();

	private TestClientDocuments() {
	}

	private static RSAKey generate() {
		try {
			return new RSAKeyGenerator(2048).keyID("agent-key").generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static String chatgptDocument() {
		return """
				{"client_id":"%s","client_name":"Shop Agent (ChatGPT형)","redirect_uris":["%s"],\
				"grant_types":["authorization_code","refresh_token"],"response_types":["code"],\
				"token_endpoint_auth_method":"private_key_jwt","token_endpoint_auth_signing_alg":"RS256",\
				"jwks_uri":"%s"}""".formatted(CHATGPT, REDIRECT_URI, JWKS);
	}

	public static String claudeDocument() {
		return """
				{"client_id":"%s","client_name":"Shop Agent (Claude형)","redirect_uris":["%s"],\
				"grant_types":["authorization_code","refresh_token"],"response_types":["code"],\
				"token_endpoint_auth_method":"none"}""".formatted(CLAUDE, REDIRECT_URI);
	}

	public static String jwks() {
		return new JWKSet(KEY.toPublicJWK()).toString();
	}

	/** RFC 7523 §3의 client assertion이다. iss와 sub는 client_id이고, aud는 RFC 7523bis대로 issuer 하나를 넣는다. */
	public static String assertion(RSAKey key, String clientId, String audience) throws JOSEException {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(clientId)
				.subject(clientId)
				.audience(audience)
				.jwtID(UUID.randomUUID().toString())
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plusSeconds(60)))
				.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}
}
