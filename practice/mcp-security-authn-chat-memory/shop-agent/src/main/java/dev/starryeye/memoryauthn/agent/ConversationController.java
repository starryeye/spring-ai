package dev.starryeye.memoryauthn.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 저장소를 직접 들여다보는 경로다. LLM 을 부르지 않는다.
 *
 * <p>부모 practice {@code chat-memory} 의 같은 컨트롤러는 <b>아무 ID 나</b>
 * 조회할 수 있었다. 사용자가 한 명이라 문제가 없었을 뿐이다.
 * 여기서는 두 가지가 다르다:
 * <ul>
 *   <li>경로에서 <b>label 만</b> 받는다. 전체 ID 를 받는 API 는 두지 않는다 —
 *       있으면 그것이 곧 구멍이다.</li>
 *   <li>목록은 현재 사용자 접두사로 <b>거른다.</b> {@code findConversationIds()} 는
 *       저장소 전체를 알고 있으므로, 거르지 않으면 남의 대화 ID 가 노출된다.</li>
 * </ul>
 */
@RestController
public class ConversationController {

    private final ChatMemory chatMemory;

    private final ChatMemoryRepository chatMemoryRepository;

    public ConversationController(ChatMemory chatMemory, ChatMemoryRepository chatMemoryRepository) {
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
    }

    /** 현재 사용자의 대화 ID 목록. 남의 것은 보이지 않는다. */
    @GetMapping("/api/conversations")
    public List<String> conversationIds(Authentication authentication) {
        String prefix = ConversationId.prefixOf(authentication);
        return chatMemoryRepository.findConversationIds().stream()
                .filter(id -> id.startsWith(prefix))
                .toList();
    }

    /** 현재 사용자의 한 대화. label 만 받으므로 남의 것을 지목할 수 없다. */
    @GetMapping("/api/conversations/{label}")
    public List<MessageView> messages(Authentication authentication, @PathVariable String label) {
        return chatMemory.get(ConversationId.of(authentication, label)).stream()
                .map(message -> new MessageView(
                        message.getMessageType().getValue(),
                        message.getText()))
                .toList();
    }

    /** 현재 사용자의 한 대화를 비운다. */
    @DeleteMapping("/api/conversations/{label}")
    public ResponseEntity<Void> clear(Authentication authentication, @PathVariable String label) {
        chatMemory.clear(ConversationId.of(authentication, label));
        return ResponseEntity.noContent().build();
    }
}
