package dev.starryeye.memoryagent;

/**
 * 저장소에 쌓인 메시지를 그대로 보여주기 위한 응답 타입.
 * "기억한다"가 마법이 아니라 이 목록이라는 것을 드러내는 것이 목적이다.
 */
public record MessageView(String role, String text) {
}
