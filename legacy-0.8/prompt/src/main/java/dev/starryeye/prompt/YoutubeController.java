package dev.starryeye.prompt;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class YoutubeController {

    private final ChatClient chatClient;
    @Value("classpath:/prompts/youtube.st")
    private Resource youtubePromptResource;

    @GetMapping("/popular")
    public String findPopularYoutuberByGenre(
            @RequestParam(value = "genre", defaultValue = "tech") String genre
    ) {
        String today = LocalDate.now().toString();

        PromptTemplate promptTemplate = new PromptTemplate(youtubePromptResource);

        Prompt prompt = promptTemplate.create(Map.of("today", today, "genre", genre));

        return chatClient.call(prompt).getResult().getOutput().getContent();
    }
}
