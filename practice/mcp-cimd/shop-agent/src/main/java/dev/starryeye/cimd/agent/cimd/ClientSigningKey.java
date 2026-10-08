package dev.starryeye.cimd.agent.cimd;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * {@code private_key_jwt}에 쓰는 agent의 서명 key다.
 *
 * <p>비밀 key는 agent만 갖고, public key는 JWKS로 문서 host에 올린다.
 * Authorization Server는 문서의 {@code jwks_uri}에서 public key를 가져와 assertion의 서명을 확인한다.
 * 그래서 Authorization Server와 미리 나눈 비밀이 없어도 client가 자신을 증명할 수 있다.
 * {@code kid}는 public key의 RFC 7638 thumbprint라 key가 바뀌면 함께 바뀐다.
 */
public record ClientSigningKey(RSAKey key) {

	public static ClientSigningKey load(Resource keyStore, String password, String alias) throws Exception {
		KeyStore store = KeyStore.getInstance("PKCS12");
		try (InputStream in = keyStore.getInputStream()) {
			store.load(in, password.toCharArray());
		}
		Certificate certificate = store.getCertificate(alias);
		Key privateKey = store.getKey(alias, password.toCharArray());
		if (certificate == null || !(privateKey instanceof RSAPrivateKey rsaPrivateKey)) {
			throw new IllegalStateException("서명 key를 찾지 못했다 (파일=%s, alias=%s)".formatted(keyStore, alias));
		}
		RSAKey key = new RSAKey.Builder((RSAPublicKey) certificate.getPublicKey())
				.privateKey(rsaPrivateKey)
				.keyUse(KeyUse.SIGNATURE)
				.algorithm(JWSAlgorithm.RS256)
				.keyIDFromThumbprint()
				.build();
		return new ClientSigningKey(key);
	}

	/**
	 * record가 만드는 기본 toString은 {@link RSAKey#toString()}을 써서 비밀 key({@code d}, {@code p}, {@code q})까지 보인다.
	 * 로그나 오류 메시지에 이 객체가 찍혀도 key가 새지 않도록 kid만 보인다.
	 */
	@Override
	public String toString() {
		return "ClientSigningKey[kid=" + this.key.getKeyID() + "]";
	}
}
