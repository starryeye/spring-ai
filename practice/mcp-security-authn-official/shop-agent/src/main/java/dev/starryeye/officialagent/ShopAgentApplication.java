package dev.starryeye.officialagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

@SpringBootApplication
public class ShopAgentApplication {

	public static void main(String[] args) {
		// SecurityContextHolder 는 thread-local 이고 ChatClient.stream() 의 리액터
		// 체인은 요청 스레드 밖에서 돈다. 이 훅을 켜면 micrometer context-propagation 이
		// Spring Security 의 SecurityContextHolderThreadLocalAccessor 를 통해
		// SecurityContext 를 리액터 경계 너머로 복원한다.
		// community 버전은 같은 문제를 Spring AI 의 internal 패키지 클래스로 풀었다.
		Hooks.enableAutomaticContextPropagation();
		SpringApplication.run(ShopAgentApplication.class, args);
	}
}
