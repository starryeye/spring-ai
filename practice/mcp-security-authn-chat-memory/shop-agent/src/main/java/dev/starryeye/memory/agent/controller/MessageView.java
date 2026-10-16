package dev.starryeye.memory.agent.controller;

/** 저장소에 쌓인 메시지를 그대로 보여 주는 응답 타입이다. */
public record MessageView(String role, String text) {
}
