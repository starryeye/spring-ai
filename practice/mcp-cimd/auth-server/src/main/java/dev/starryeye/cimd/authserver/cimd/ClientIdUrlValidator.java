package dev.starryeye.cimd.authserver.cimd;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * CIMD 문서 주소(client_id)와 {@code jwks_uri}의 규칙을 검사한다.
 *
 * <p>문서 주소는 곧 client의 이름이라, 같은 문서를 여러 이름으로 가리키지 못하게 주소 형식을 좁힌다.
 * {@code https}이고 path가 있으며, {@code .}·{@code ..} path 조각, fragment, 사용자 정보가 없어야 한다(CIMD draft §3).
 * query는 draft가 권하지 않으므로 이 서버는 받지 않는다.
 *
 * <p>Authorization Server는 client가 고른 주소로 직접 요청을 보낸다.
 * 그래서 주소가 내부망을 가리키면 거절한다(SSRF, CIMD draft §6.5).
 * 학습 환경의 agent 문서 host 하나만 예외로 둔다. 예외는 scheme·host·port가 모두 같을 때만 적용한다.
 * 검사한 뒤 실제로 연결할 때 DNS가 다른 주소를 돌려주는 경우(DNS rebinding)는 막지 않는다.
 *
 * <p>host 조회(DNS)에는 시간 제한을 둔다. host는 client가 고르므로, 응답이 느린 DNS 서버를 가리키면 요청 thread가 그동안 묶인다.
 * JDK의 {@link InetAddress#getAllByName}에는 시간 제한이 없어서, 조회는 별도 thread에서 하고 기다리는 시간만 자른다.
 * 시간을 넘긴 조회 thread는 OS의 조회가 끝날 때까지 남으므로, 조회 thread 수에도 상한을 두고 자리가 없으면 바로 실패한다.
 */
public final class ClientIdUrlValidator {

	/** 동시에 진행하는 host 조회 thread 수의 상한이다. 느린 DNS가 thread를 끝없이 늘리지 못하게 한다. */
	private static final int MAX_RESOLVER_THREADS = 16;

	private static final Duration DEFAULT_RESOLVE_TIMEOUT = Duration.ofSeconds(2);

	private final URI loopbackException;

	private final HostResolver resolver;

	private final Duration resolveTimeout;

	private final ExecutorService resolverThreads = new ThreadPoolExecutor(0, MAX_RESOLVER_THREADS, 30, TimeUnit.SECONDS,
			new SynchronousQueue<>(), Thread.ofPlatform().name("cimd-dns-", 0).daemon().factory());

	public ClientIdUrlValidator(String loopbackException, HostResolver resolver) {
		this(loopbackException, resolver, DEFAULT_RESOLVE_TIMEOUT);
	}

	/**
	 * @param resolveTimeout host 조회를 기다리는 시간. 설정의 연결 시간 제한({@code mcp.cimd.connect-timeout})과 같은 값을 쓴다
	 */
	public ClientIdUrlValidator(String loopbackException, HostResolver resolver, Duration resolveTimeout) {
		this.loopbackException = (loopbackException == null || loopbackException.isBlank())
				? null : URI.create(loopbackException);
		this.resolver = resolver;
		this.resolveTimeout = resolveTimeout;
	}

	public URI validate(String value) {
		URI uri = parse(value);
		if (!"https".equals(uri.getScheme())) {
			throw invalid("https가 아니다", value);
		}
		if (uri.getHost() == null) {
			throw invalid("host가 없다", value);
		}
		if (uri.getRawUserInfo() != null) {
			throw invalid("사용자 정보가 있다", value);
		}
		if (uri.getRawFragment() != null) {
			throw invalid("fragment가 있다", value);
		}
		if (uri.getRawQuery() != null) {
			throw invalid("query가 있다", value);
		}
		String path = uri.getPath();
		if (path == null || path.isEmpty() || "/".equals(path)) {
			throw invalid("path가 없다", value);
		}
		for (String segment : path.split("/")) {
			if (".".equals(segment) || "..".equals(segment)) {
				throw invalid("`" + segment + "` path 조각이 있다", value);
			}
		}
		if (!isLoopbackException(uri)) {
			requirePublicAddress(uri);
		}
		return uri;
	}

	/** loopback·사설·link-local·any-local·multicast·IPv6 고유 주소(fc00::/7)면 내부 주소다. */
	static boolean isInternal(InetAddress address) {
		if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
				|| address.isAnyLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		if (address instanceof Inet6Address) {
			byte[] bytes = address.getAddress();
			// IPv4-mapped IPv6 주소(::ffff:a.b.c.d)는 내부 IPv4를 가리킬 수 있다.
			if (isIPv4MappedIPv6(bytes)) {
				try {
					InetAddress ipv4 = InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
					return isInternal(ipv4);
				}
				catch (UnknownHostException ex) {
					// InetAddress.getByAddress는 DNS를 안 하므로 이 예외는 발생하지 않는다.
					// 그래도 발생하면 확인하지 못한 주소이므로 내부 주소로 본다.
					return true;
				}
			}
			// IPv6 고유 주소는 isSiteLocalAddress가 잡지 않는다.
			return (bytes[0] & 0xfe) == 0xfc;
		}
		return false;
	}

	/** 첫 10바이트가 0이고 10-11 바이트가 0xff면 IPv4-mapped IPv6 주소다. */
	private static boolean isIPv4MappedIPv6(byte[] bytes) {
		for (int i = 0; i < 10; i++) {
			if (bytes[i] != 0) {
				return false;
			}
		}
		return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
	}

	private boolean isLoopbackException(URI uri) {
		return this.loopbackException != null
				&& this.loopbackException.getScheme().equalsIgnoreCase(uri.getScheme())
				&& this.loopbackException.getHost().equalsIgnoreCase(uri.getHost())
				&& port(this.loopbackException) == port(uri);
	}

	private void requirePublicAddress(URI uri) {
		for (InetAddress address : resolve(uri.getHost())) {
			if (isInternal(address)) {
				throw invalid("내부 주소(" + address.getHostAddress() + ")를 가리킨다", uri.toString());
			}
		}
	}

	private InetAddress[] resolve(String host) {
		Future<InetAddress[]> lookup;
		try {
			lookup = this.resolverThreads.submit(() -> this.resolver.resolve(host));
		}
		catch (RejectedExecutionException ex) {
			throw new InvalidClientMetadataException("host 조회가 상한(%d)만큼 밀려 있다: %s".formatted(MAX_RESOLVER_THREADS, host), ex);
		}
		try {
			return lookup.get(this.resolveTimeout.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException ex) {
			lookup.cancel(true);
			throw new InvalidClientMetadataException("host 조회가 시간 제한(%s)을 넘었다: %s".formatted(this.resolveTimeout, host), ex);
		}
		catch (ExecutionException ex) {
			throw new InvalidClientMetadataException("host를 찾지 못했다: " + host, ex.getCause());
		}
		catch (InterruptedException ex) {
			lookup.cancel(true);
			Thread.currentThread().interrupt();
			throw new InvalidClientMetadataException("host를 찾지 못했다: " + host, ex);
		}
	}

	private static URI parse(String value) {
		try {
			return new URI(value);
		}
		catch (URISyntaxException | NullPointerException ex) {
			throw new InvalidClientMetadataException("주소 형식이 아니다: " + value);
		}
	}

	private static int port(URI uri) {
		return (uri.getPort() == -1) ? 443 : uri.getPort();
	}

	private static InvalidClientMetadataException invalid(String reason, String value) {
		return new InvalidClientMetadataException(reason + ": " + value);
	}
}
