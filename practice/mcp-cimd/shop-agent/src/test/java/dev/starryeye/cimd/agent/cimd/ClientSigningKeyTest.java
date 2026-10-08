package dev.starryeye.cimd.agent.cimd;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ClientSigningKeyTest {

	@Test
	void PKCS12의_RSA_key를_읽고_kid는_thumbprint다() throws Exception {
		ClientSigningKey signingKey = ClientSigningKey.load(new ClassPathResource("test-certs/client-signing.p12"),
				"changeit", "client-signing");

		assertThat(signingKey.key().isPrivate()).isTrue();
		assertThat(signingKey.key().getKeyID()).isEqualTo(signingKey.key().computeThumbprint().toString());
		assertThat(signingKey.key().getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
		assertThat(signingKey.key().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
		// 인증서 정보(x5c)는 문서에 올릴 필요가 없다.
		assertThat(signingKey.key().getX509CertChain()).isNull();
	}

	@Test
	void toString은_kid만_보이고_비밀_key는_보이지_않는다() throws Exception {
		ClientSigningKey signingKey = ClientSigningKey.load(new ClassPathResource("test-certs/client-signing.p12"),
				"changeit", "client-signing");

		// RSAKey의 toString은 비밀 key(d, p, q)까지 JSON으로 보인다. 로그에 남으면 key가 샌다.
		assertThat(signingKey.toString()).contains(signingKey.key().getKeyID()).doesNotContain("\"d\"");
	}

	@Test
	void alias가_없으면_이유를_남기고_멈춘다() {
		assertThatIllegalStateException()
				.isThrownBy(() -> ClientSigningKey.load(new ClassPathResource("test-certs/client-signing.p12"),
						"changeit", "missing"))
				.withMessageContaining("missing");
	}
}
