package dev.starryeye.prompt;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class JokeController {

    private final ChatClient chatClient;

    @GetMapping("/api/jokes")
    public String jokes() {

        /**
         * SystemMessage 와 UserMessage 로 구분하여 답변을 한정지을 수 있다.
         */
        var system = new SystemMessage("당신의 주요 기능은 아재 개그를 말해주는 것입니다. 누군가가 다른 유형의 농담을 요청하면 아재 개그만 알고 있다고 말해주세요.");
        var user = new UserMessage("지구에 관한 아주 진지한 농담을 말해줘");
        Prompt prompt = new Prompt(List.of(system, user));

        return chatClient.call(prompt).getResult().getOutput().getContent();
    }
}
