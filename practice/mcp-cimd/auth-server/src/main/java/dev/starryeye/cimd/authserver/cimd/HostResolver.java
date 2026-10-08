package dev.starryeye.cimd.authserver.cimd;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * host 이름을 IP 주소로 푼다. 테스트가 DNS 없이 주소를 정할 수 있게 interface로 둔다.
 */
@FunctionalInterface
public interface HostResolver {

	HostResolver SYSTEM = InetAddress::getAllByName;

	InetAddress[] resolve(String host) throws UnknownHostException;
}
