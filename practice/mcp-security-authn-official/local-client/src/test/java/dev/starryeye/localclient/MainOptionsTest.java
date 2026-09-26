package dev.starryeye.localclient;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainOptionsTest {

	@Test
	void 인자가_없으면_official의_기본_주소를_쓴다() {
		Main.Options options = Main.Options.parse(new String[0]);

		assertThat(options.resourceUrl()).isEqualTo("http://localhost:8111/mcp");
		assertThat(options.issuer()).isEqualTo("http://localhost:9010");
		assertThat(options.openBrowser()).isTrue();
	}

	@Test
	void 인자로_주소와_browser_여부를_바꾼다() {
		Main.Options options = Main.Options.parse(new String[] {
				"--resource", "https://mcp.example.com/mcp", "--issuer", "https://auth.example.com", "--no-browser" });

		assertThat(options.resourceUrl()).isEqualTo("https://mcp.example.com/mcp");
		assertThat(options.issuer()).isEqualTo("https://auth.example.com");
		assertThat(options.openBrowser()).isFalse();
	}

	@Test
	void 모르는_인자는_알려_준다() {
		assertThatThrownBy(() -> Main.Options.parse(new String[] { "--port" }))
				.isInstanceOf(LocalClientException.class).hasMessageContaining("--port");
	}
}
