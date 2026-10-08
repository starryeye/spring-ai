package dev.starryeye.cimd.authserver.cimd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIdMetadataDocumentPropertiesTest {

	@Test
	void trust_bundle을_비워_두면_정하지_않은_것으로_본다() {
		// 설정 파일에 `trust-bundle:`만 쓰면 빈 문자열이 들어온다. 그때도 JVM 기본 truststore를 쓴다.
		assertThat(properties(null).trustBundle()).isNull();
		assertThat(properties("").trustBundle()).isNull();
		assertThat(properties("  ").trustBundle()).isNull();
		assertThat(properties("client-metadata-trust").trustBundle()).isEqualTo("client-metadata-trust");
	}

	static ClientIdMetadataDocumentProperties properties(String trustBundle) {
		return new ClientIdMetadataDocumentProperties(null, null, null, null, null, null, trustBundle, null);
	}
}
