package dev.starryeye.memoryauthn.agent;

/** 저장소에 쌓인 메시지를 그대로 보여주기 위한 응답 타입. */
public record MessageView(String role, String text) {
}
