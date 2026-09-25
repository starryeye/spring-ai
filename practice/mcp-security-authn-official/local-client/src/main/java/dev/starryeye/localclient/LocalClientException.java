package dev.starryeye.localclient;

/** local client가 멈추는 모든 이유. 메시지 한 줄로 무엇이 틀렸는지 알려 준다. */
public class LocalClientException extends RuntimeException {

	public LocalClientException(String message) {
		super(message);
	}

	public LocalClientException(String message, Throwable cause) {
		super(message, cause);
	}
}
