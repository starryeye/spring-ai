package dev.starryeye.cimd.authserver.cimd;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;

/** 테스트용 localhost 인증서로 HTTPS 서버와 client의 SSLContext를 만든다. */
public final class TestTls {

	static final char[] PASSWORD = "changeit".toCharArray();

	private TestTls() {
	}

	public static SSLContext server() throws Exception {
		KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keys.init(load("test-certs/localhost.p12"), PASSWORD);
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(keys.getKeyManagers(), null, null);
		return context;
	}

	public static SSLContext client() throws Exception {
		TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trust.init(load("test-certs/localhost-trust.p12"));
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(null, trust.getTrustManagers(), null);
		return context;
	}

	private static KeyStore load(String path) throws Exception {
		KeyStore store = KeyStore.getInstance("PKCS12");
		try (InputStream in = TestTls.class.getClassLoader().getResourceAsStream(path)) {
			store.load(in, PASSWORD);
		}
		return store;
	}
}
