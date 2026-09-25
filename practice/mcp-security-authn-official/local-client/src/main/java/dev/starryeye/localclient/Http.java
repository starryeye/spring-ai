package dev.starryeye.localclient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

/** 요청을 보내고 본문을 문자열로 받는다. 연결 실패와 timeout은 {@link LocalClientException}으로 바꾼다. */
final class Http {

	private Http() {
	}

	static HttpResponse<String> send(HttpClient http, HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (HttpTimeoutException ex) {
			throw new LocalClientException(request.uri() + "에서 응답이 시간 안에 오지 않았다", ex);
		}
		catch (IOException ex) {
			throw new LocalClientException(request.uri() + "에 연결하지 못했다", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new LocalClientException("요청을 기다리다 중단됐다", ex);
		}
	}
}
