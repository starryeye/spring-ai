package dev.starryeye.localclient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE(RFC 7636)의 verifier와 challenge.
 *
 * <p>public client에는 client_secret이 없다. 누가 authorization code를 가로채도 verifier가 없으면
 * token으로 바꾸지 못하게 하는 것이 PKCE다. challenge는 verifier의 SHA-256을 base64url로 쓴 값이다(S256).
 */
public record Pkce(String verifier, String challenge) {

	private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

	private static final SecureRandom RANDOM = new SecureRandom();

	/** 32 byte 난수로 43자 verifier를 만든다. */
	public static Pkce generate() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return fromVerifier(BASE64URL.encodeToString(bytes));
	}

	public static Pkce fromVerifier(String verifier) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return new Pkce(verifier, BASE64URL.encodeToString(hash));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
