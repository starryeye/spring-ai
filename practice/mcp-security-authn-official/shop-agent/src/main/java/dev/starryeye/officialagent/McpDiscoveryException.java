package dev.starryeye.officialagent;

/** Authorization Server discovery가 명세대로 끝나지 않았다는 예외다. 이때는 token을 받으러 가지 않는다. */
public class McpDiscoveryException extends RuntimeException {

	public McpDiscoveryException(String message) {
		super(message);
	}
}
