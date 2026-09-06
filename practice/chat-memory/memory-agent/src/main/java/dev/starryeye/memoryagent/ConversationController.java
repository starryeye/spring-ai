package dev.starryeye.memoryagent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 저장소를 직접 들여다보는 경로다. LLM 을 부르지 않는다.
 *
 * <p>{@link ChatMemory} 와 {@link ChatMemoryRepository} 를 둘 다 주입받는 이유:
 * 대화 ID 목록({@code findConversationIds})은 <b>저장소에만</b> 있고
 * {@code ChatMemory} 인터페이스에는 없다. 둘의 역할이 다르다는 것이 여기서 드러난다.
 */
@RestController
public class ConversationController {

    private final ChatMemory chatMemory;

    private final ChatMemoryRepository chatMemoryRepository;

    public ConversationController(ChatMemory chatMemory, ChatMemoryRepository chatMemoryRepository) {
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
    }

    /** 저장된 대화 ID 목록. ChatMemory 에는 없는 기능이라 저장소를 직접 쓴다. */
    @GetMapping("/api/conversations")
    public List<String> conversationIds() {
        return chatMemoryRepository.findConversationIds();
    }

    /** 한 대화에 쌓인 메시지를 순서대로. */
    @GetMapping("/api/conversations/{conversationId}")
    public List<MessageView> messages(@PathVariable String conversationId) {
        return chatMemory.get(conversationId).stream()
                .map(message -> new MessageView(
                        message.getMessageType().getValue(),
                        message.getText()))
                .toList();
    }

    /** 한 대화를 비운다. 이후 같은 ID 로 물으면 기억이 없다. */
    @DeleteMapping("/api/conversations/{conversationId}")
    public ResponseEntity<Void> clear(@PathVariable String conversationId) {
        chatMemory.clear(conversationId);
        return ResponseEntity.noContent().build();
    }
}
