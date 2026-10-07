package dev.starryeye.cimd.authserver.cimd;

import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ClientIdUrlValidatorTest {

	/**
	 * DNS를 쓰지 않고 host별 주소를 정해 둔다.
	 * 표에 없는 host가 IP literal이면 그대로 풀고, 아니면 찾지 못한 것으로 본다.
	 */
	static HostResolver 주소표(Map<String, String> table) {
		return host -> {
			String address = table.get(host);
			if (address == null && host.matches("[0-9.]+|\\[?[0-9a-fA-F:]+]?")) {
				address = host.replace("[", "").replace("]", "");
			}
			if (address == null) {
				throw new UnknownHostException(host);
			}
			return new InetAddress[] { InetAddress.getByName(address) };
		};
	}

	static final HostResolver 주소 = 주소표(Map.of(
			"example.com", "93.184.216.34",
			"internal.example", "10.0.0.5",
			"metadata.example", "169.254.169.254",
			"v6.example", "fd00::1",
			"localhost", "127.0.0.1"));

	ClientIdUrlValidator validator = new ClientIdUrlValidator("https://localhost:8172", 주소);

	@Test
	void 공개_주소의_https_문서_주소는_통과한다() {
		assertThat(this.validator.validate("https://example.com/oauth/client.json"))
				.hasToString("https://example.com/oauth/client.json");
	}

	@Test
	void https가_아니면_거절한다() {
		거절("http://example.com/oauth/client.json", "https");
	}

	@Test
	void path가_없으면_거절한다() {
		거절("https://example.com", "path");
		거절("https://example.com/", "path");
	}

	@Test
	void 점_path_조각이_있으면_거절한다() {
		거절("https://example.com/oauth/../client.json", "..");
		거절("https://example.com/./client.json", ".");
		거절("https://example.com/oauth/%2e%2e/client.json", "..");
		거절("https://example.com/%2E/client.json", ".");
	}

	@Test
	void fragment_사용자_정보_query가_있으면_거절한다() {
		거절("https://example.com/client.json#x", "fragment");
		거절("https://user:pass@example.com/client.json", "사용자 정보");
		거절("https://example.com/client.json?v=1", "query");
	}

	@Test
	void 사설_link_local_loopback_IPv6_고유_주소를_가리키면_거절한다() {
		거절("https://internal.example/client.json", "10.0.0.5");
		거절("https://metadata.example/client.json", "169.254.169.254");
		거절("https://v6.example/client.json", "fd00");
		거절("https://localhost/client.json", "127.0.0.1");
	}

	@Test
	void host를_찾지_못하면_거절한다() {
		거절("https://unknown.example/client.json", "host");
	}

	@Test
	void 예외_주소는_scheme_host_port가_모두_같을_때만_통과한다() {
		// 예외 주소는 DNS를 보지 않는다. 표가 비어도 통과해야 한다.
		ClientIdUrlValidator strict = new ClientIdUrlValidator("https://localhost:8172", 주소표(Map.of()));
		assertThat(strict.validate("https://localhost:8172/oauth/client.json")).isNotNull();

		거절(this.validator, "https://localhost:8173/oauth/client.json", "127.0.0.1");
		거절(this.validator, "http://localhost:8172/oauth/client.json", "https");
		거절(this.validator, "https://127.0.0.1:8172/oauth/client.json", "127.0.0.1");
	}

	@Test
	void 예외_주소가_없으면_localhost도_거절한다() {
		ClientIdUrlValidator noException = new ClientIdUrlValidator(null, 주소);
		거절(noException, "https://localhost:8172/oauth/client.json", "127.0.0.1");
	}

	@Test
	void IPv4_mapped_IPv6_주소의_내부_IPv4를_감지한다() throws UnknownHostException {
		// ::ffff:127.0.0.1 (loopback의 IPv4-mapped IPv6)
		byte[] loopbackMapped = new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 127, 0, 0, 1 };
		Inet6Address loopbackIPv6Mapped = Inet6Address.getByAddress(null, loopbackMapped, null);
		assertThat(ClientIdUrlValidator.isInternal(loopbackIPv6Mapped)).isTrue();

		// ::ffff:93.184.216.34 (공개 주소의 IPv4-mapped IPv6)
		byte[] publicMapped = new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 93, (byte) 184, (byte) 216, 34 };
		Inet6Address publicIPv6Mapped = Inet6Address.getByAddress(null, publicMapped, null);
		assertThat(ClientIdUrlValidator.isInternal(publicIPv6Mapped)).isFalse();
	}

	void 거절(String value, String reason) {
		거절(this.validator, value, reason);
	}

	static void 거절(ClientIdUrlValidator validator, String value, String reason) {
		assertThatExceptionOfType(InvalidClientMetadataException.class)
				.isThrownBy(() -> validator.validate(value))
				.withMessageContaining(reason);
	}
}
