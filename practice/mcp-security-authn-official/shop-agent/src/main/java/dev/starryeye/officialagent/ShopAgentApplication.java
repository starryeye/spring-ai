package dev.starryeye.officialagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

@SpringBootApplication
public class ShopAgentApplication {

	public static void main(String[] args) {
		// SecurityContextHolder는 thread-local이고, ChatClient.stream()의 reactor chain은 요청 thread 밖에서 돈다.
		// 이 hook을 켜면 micrometer context-propagation이 Spring Security의
		// SecurityContextHolderThreadLocalAccessor로 SecurityContext를 reactor의 thread에 옮겨 준다.
		// community practice는 같은 문제를 module의 AuthenticationMcpTransportContextProvider.writeToReactorContext()와
		// ChatController의 .contextWrite(...)로 푼다.
		Hooks.enableAutomaticContextPropagation();
		SpringApplication.run(ShopAgentApplication.class, args);
	}
}
