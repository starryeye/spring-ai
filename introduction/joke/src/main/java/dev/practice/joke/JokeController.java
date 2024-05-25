package dev.practice.joke;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class JokeController {

    private final ChatClient chatClient;
    // openAI 라이브러리를 종속하였으므로 ChatClient 인터페이스의 구현체는 OpenAiChatClient 가 된다.

    @GetMapping("/jokes")
    public String generateJokes(
            @RequestParam(value = "message", defaultValue = "농담 해줘") String message
    ) {
        return chatClient.call(message);
    }
}
