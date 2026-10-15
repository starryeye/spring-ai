package dev.starryeye.memory.agent.discovery;

/** 인가 서버 발견이 명세대로 끝나지 않았다. 이럴 때는 토큰을 받으러 가지 않는다. */
public class McpDiscoveryException extends RuntimeException {

    public McpDiscoveryException(String message) {
        super(message);
    }

    /** 연결 실패나 깨진 응답처럼 다른 예외 때문에 멈췄다면 그 예외를 원인으로 남긴다. stack trace에서 실패한 곳을 찾을 수 있다. */
    public McpDiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
