package dev.starryeye.official.agent.controller;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ChatControllerLlmErrorTest {

    @Test
    void Claude_API_오류는_안내_문장으로_끝낸다() {
        ChatModel model = mock(ChatModel.class);
        given(model.getOptions()).willReturn(ChatOptions.builder().build());
        given(model.stream(any(Prompt.class))).willReturn(Flux.error(UnauthorizedException.builder()
                .headers(Headers.builder().build())
                .body(JsonValue.from(Map.of("error", Map.of("type", "authentication_error"))))
                .build()));
        ChatController controller = new ChatController(ChatClient.builder(model).build());

        List<String> answer = controller.chat("재고?").collectList().block(Duration.ofSeconds(10));

        assertThat(String.join("", answer)).contains("key가 올바르지 않습니다");
    }
}
