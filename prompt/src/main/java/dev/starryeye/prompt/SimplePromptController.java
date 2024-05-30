package dev.starryeye.prompt;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SimplePromptController {

    private final ChatClient chatClient;

    @GetMapping("")
    public String simple() {
        return chatClient.call(
                new Prompt("농담 해줘")
        ).getResult().getOutput().getContent();
    }

}
