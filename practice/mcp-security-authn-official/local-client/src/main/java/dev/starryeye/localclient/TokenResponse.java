package dev.starryeye.localclient;

/** token endpoint의 응답 중 이 client가 쓰는 값. public client에는 refresh token이 오지 않는다. */
public record TokenResponse(String accessToken, long expiresIn) {
}
