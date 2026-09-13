package dev.starryeye.officialagent;

/** 인가 서버 발견이 명세대로 끝나지 않았다. 이럴 때는 토큰을 받으러 가지 않는다. */
public class McpDiscoveryException extends RuntimeException {

	public McpDiscoveryException(String message) {
		super(message);
	}
}
