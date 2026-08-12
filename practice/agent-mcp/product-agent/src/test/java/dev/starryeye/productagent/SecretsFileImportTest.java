package dev.starryeye.productagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * secrets.yml 로 API 키를 주입하는 경로가 실제로 동작하는지 검증한다.
 *
 * <p>application.yml 의 {@code spring.config.import: optional:file:./secrets.yml} 가
 * 파일의 {@code OPENAI_API_KEY} 를 읽고, application-openai.yml 의
 * {@code api-key: ${OPENAI_API_KEY}} 가 그 값으로 해석되는 전체 사슬을 확인한다.
 * 여기서는 실제 secrets.yml 대신 테스트 픽스처를 import 한다.
 */
@SpringBootTest(properties = {
        "spring.config.import=optional:file:./src/test/resources/secrets-test.yml",
        "spring.ai.mcp.client.enabled=false"
})
@ActiveProfiles("openai")
class SecretsFileImportTest {

    @Autowired
    Environment environment;

    @Test
    void secrets_파일의_키가_api_key_속성으로_해석된다() {
        assertThat(environment.getProperty("spring.ai.openai.api-key"))
                .isEqualTo("test-key-from-secrets-file");
    }
}
