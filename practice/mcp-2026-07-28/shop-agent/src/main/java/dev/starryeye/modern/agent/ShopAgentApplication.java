package dev.starryeye.modern.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ShopAgentApplication {

	public static void main(String[] args) {
		// reactor thread로 SecurityContext를 옮기는 설정은 application.yml의 spring.reactor.context-propagation이다.
		SpringApplication.run(ShopAgentApplication.class, args);
	}
}
