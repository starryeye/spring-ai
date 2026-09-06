# chat-memory 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ChatMemory` · `MessageChatMemoryAdvisor` · `conversationId` 셋이 어떻게 맞물리는지 직접 배선하고, 대화가 이어지는 것과 대화끼리 격리되는 것을 눈으로 확인한다.

**Architecture:** Spring Boot 앱 하나(`memory-agent` :8120). MCP 도 인증도 없다. `ChatMemory` 빈을 직접 정의해 `maxMessages` 를 통제하고, `MessageChatMemoryAdvisor` 를 `ChatClient` 에 기본 어드바이저로 붙인 뒤, 요청마다 `ChatMemory.CONVERSATION_ID` 파라미터로 어느 대화인지 지정한다. 저장소를 들여다보는 조회·삭제 엔드포인트가 이 practice 의 절반이다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring AI 2.0.0, Ollama `qwen3:8b`

**Spec:** [docs/superpowers/specs/2026-09-05-chat-memory-design.md](../specs/2026-09-05-chat-memory-design.md)

---

## Global Constraints

### 절대 건드리지 않을 것

- `practice/agent-mcp/**`, `practice/agent-mcps/**`, `practice/mcp-security-authn-community/**`, `practice/mcp-security-authn-official/**` — **한 줄도 수정하지 않는다.** 읽기만 허용. 넷 다 종단 검증이 끝나 있다.
- `legacy-0.8/**`, 루트 `.gitignore`
- 루트 `settings.gradle` 은 이 저장소에 **없다.** 만들지 않는다. 각 프로젝트가 독립 빌드다.
- `.superpowers/` 는 절대 `git add` 하지 않는다. `docs/superpowers/**` 도 커밋하지 않는다.
- 예외: **Task 3 에서 루트 `README.md` 의 practice 목록에 항목을 추가한다.** 추가만, 기존 행 유지.

### 이 practice 에 없는 것

MCP, Spring Security, 인증, 인가. 앱이 하나뿐이고 `build.gradle` 에 그런 의존성이 없어야 한다.

### 버전 고정

- Java 21 (`languageVersion = JavaLanguageVersion.of(21)`). 시스템 기본은 17이라 toolchain 필수.
  **`gradle.properties` 에 JDK 절대경로를 넣지 않는다** — Gradle 자동탐지가 이 머신의 sdkman JDK 를 찾는다(검증됨).
- Spring Boot `4.1.0`, `io.spring.dependency-management` `1.1.7`, Spring AI BOM `2.0.0`
- `group = 'dev.starryeye'`, 패키지 `dev.starryeye.memoryagent`
- 포트 **8120** — 앞선 네 practice(8080~8082, 8090~8092, 9000·8100·8101, 9010·8110·8111)와 겹치지 않는다

### 실측으로 확인된 API — 이대로 쓴다

`javap` 로 확인했다. 추측하지 말 것.

```java
// org.springframework.ai.chat.memory
public interface ChatMemory {
    String CONVERSATION_ID = "chat_memory_conversation_id";
    void add(String conversationId, List<Message> messages);   // 단건 오버로드도 있음
    List<Message> get(String conversationId);
    void clear(String conversationId);
}

public interface ChatMemoryRepository {
    List<String> findConversationIds();
    List<Message> findByConversationId(String conversationId);
    void saveAll(String conversationId, List<Message> messages);
    void deleteByConversationId(String conversationId);
}

MessageWindowChatMemory.builder()
        .chatMemoryRepository(ChatMemoryRepository)
        .maxMessages(int)
        .build();

// org.springframework.ai.chat.client.advisor
MessageChatMemoryAdvisor.builder(ChatMemory).build();   // order(int), scheduler(..) 도 있음

// 요청별 대화 지정
chatClient.prompt().advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))

// 메시지에서 값 꺼내기
message.getMessageType()   // MessageType.USER / ASSISTANT / SYSTEM / TOOL
message.getText()          // Content 인터페이스가 선언, Message 가 상속
new UserMessage(String), new AssistantMessage(String)
```

**`ChatMemory` 와 `ChatMemoryRepository` 빈은 자동설정이 공짜로 준다**
(`ChatMemoryAutoConfiguration`, `@ConditionalOnMissingBean`). 그 자동설정 아티팩트
(`spring-ai-autoconfigure-model-chat-memory`)는 모델 스타터가 이미 전이 의존으로 끌어오므로
**`build.gradle` 에 따로 추가할 필요가 없다.**

이 practice 는 `ChatMemory` 를 **직접 정의한다** — `maxMessages` 를 실험해야 하기 때문이다.
정의하는 순간 자동설정이 물러난다. `ChatMemoryRepository` 는 자동설정 것(`InMemoryChatMemoryRepository`)을 그대로 쓴다.

### 앞 practice 에서 값을 치른 것들 — 다시 발견하지 말 것

| 항목 | 내용 |
|---|---|
| 모델 타입 | `spring.ai.model.{audio.speech, audio.transcription, embedding, image, moderation}: none` — 안 끄면 다른 스타터가 API 키를 요구해 기동 실패 |
| 응답 | `text/plain;charset=UTF-8` (SSE 는 토큰마다 `data:` 가 붙어 못 읽는다) |
| ollama | `spring.ai.ollama.chat.think: low` (미지정은 수 분, `false` 는 지시를 못 따른다) |
| 속성 | `spring.ai.ollama.chat.model` (`chat.options.*` 아님) |
| Boot 4.1 | `@AutoConfigureMockMvc` 는 `org.springframework.boot.webmvc.test.autoconfigure` 패키지. `testImplementation 'org.springframework.boot:spring-boot-webmvc-test'` 필요 |
| 빌드 | Initializr 로 생성, `bootVersion=4.1.0` (`4.1.0.RELEASE` 는 HTTP 500). 생성된 `HELP.md`·`application.properties` 는 삭제 |
| 스크립트 | 백그라운드는 `< /dev/null` + `disown`. `stop.sh` 는 `sleep` 후 `kill -9` 로 잔존 프로세스 정리 |
| 테스트 | 한국어 메서드명, AssertJ |

MCP 서버가 없으므로 `@McpTool` 반환 타입 규칙은 해당 없다.

---

## File Structure

```
practice/chat-memory/
├── README.md                     # 설명 + 관측된 검증 결과
├── run.sh / stop.sh
├── .gitignore                    # logs/
└── memory-agent/                 # :8120
    ├── build.gradle
    └── src/main/java/dev/starryeye/memoryagent/
    │   ├── MemoryAgentApplication.java
    │   ├── ChatMemoryConfig.java      # ChatMemory 빈 직접 정의 (maxMessages 통제)
    │   ├── ChatClientConfig.java      # 시스템 프롬프트 + defaultAdvisors
    │   ├── ChatController.java        # POST /api/chat
    │   ├── ConversationController.java# 저장소 조회·삭제
    │   └── MessageView.java           # 조회 응답용 record
    └── src/main/resources/
        ├── application.yml
        └── static/index.html
```

대화 엔드포인트를 `ChatController` 와 분리한 이유: 전자는 **LLM 을 부르는 경로**,
후자는 **저장소를 직접 읽는 경로**다. 책임이 다르고, 후자는 이 practice 의 관측 도구다.

---

## Task 1: memory-agent — 배선과 저장소 조회

이 practice 의 본체다. 앱 하나를 통째로 만든다.

**Files:**
- Create: `practice/chat-memory/memory-agent/build.gradle`
- Create: `.../memory-agent/src/main/java/dev/starryeye/memoryagent/MemoryAgentApplication.java`
- Create: `.../memory-agent/src/main/java/dev/starryeye/memoryagent/ChatMemoryConfig.java`
- Create: `.../memory-agent/src/main/java/dev/starryeye/memoryagent/ChatClientConfig.java`
- Create: `.../memory-agent/src/main/java/dev/starryeye/memoryagent/ChatController.java`
- Create: `.../memory-agent/src/main/java/dev/starryeye/memoryagent/ConversationController.java`
- Create: `.../memory-agent/src/main/java/dev/starryeye/memoryagent/MessageView.java`
- Create: `.../memory-agent/src/main/resources/application.yml`
- Test: `.../memory-agent/src/test/java/dev/starryeye/memoryagent/ChatMemoryConfigTest.java`
- Test: `.../memory-agent/src/test/java/dev/starryeye/memoryagent/ConversationControllerTest.java`
- Test: `.../memory-agent/src/test/java/dev/starryeye/memoryagent/MemoryAgentApplicationTests.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `POST /api/chat?conversationId=<id>` — 본문 평문, 응답 `text/plain` 스트리밍
  - `GET /api/conversations` → `List<String>`
  - `GET /api/conversations/{id}` → `List<MessageView>`
  - `DELETE /api/conversations/{id}` → 204
  - `MessageView(String role, String text)`
  - 속성 `chat.memory.max-messages` (기본 20)

- [ ] **Step 1: Initializr 로 뼈대 생성**

```bash
mkdir -p practice/chat-memory && cd practice/chat-memory
curl -sfL https://start.spring.io/starter.zip \
  -d type=gradle-project -d language=java -d bootVersion=4.1.0 \
  -d groupId=dev.starryeye -d artifactId=memory-agent -d name=memory-agent \
  -d packageName=dev.starryeye.memoryagent -d javaVersion=21 \
  -d dependencies=web \
  -o memory-agent.zip && unzip -q memory-agent.zip -d memory-agent && rm memory-agent.zip
```

생성된 `HELP.md` 와 `application.properties` 는 삭제한다.

- [ ] **Step 2: build.gradle 교체**

```gradle
plugins {
	id 'java'
	id 'org.springframework.boot' version '4.1.0'
	id 'io.spring.dependency-management' version '1.1.7'
}

group = 'dev.starryeye'
version = '0.0.1-SNAPSHOT'

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

ext {
	set('springAiVersion', "2.0.0")
}

dependencies {
	implementation 'org.springframework.boot:spring-boot-starter-web'
	// 모델 스타터가 spring-ai-autoconfigure-model-chat-memory 를 전이 의존으로
	// 끌어온다 — ChatMemory 용 의존성을 따로 적을 필요가 없다.
	implementation 'org.springframework.ai:spring-ai-starter-model-ollama'
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testImplementation 'org.springframework.boot:spring-boot-webmvc-test'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

dependencyManagement {
	imports {
		mavenBom "org.springframework.ai:spring-ai-bom:${springAiVersion}"
	}
}

tasks.named('test') {
	useJUnitPlatform()
}
```

MCP·Security 의존성이 **없어야** 한다.

- [ ] **Step 3: application.yml 작성**

```yaml
server:
  port: 8120

spring:
  application:
    name: memory-agent
  ai:
    model:
      chat: ollama
      # chat 외의 모델 자동설정을 끄지 않으면 다른 스타터가 API 키를 요구해 기동이 실패한다.
      audio:
        speech: none
        transcription: none
      embedding: none
      image: none
      moderation: none
    ollama:
      chat:
        model: qwen3:8b
        temperature: 0.1
        # 미지정이면 수 분이 걸리고, false 면 지시를 못 따른다.
        think: low

chat:
  memory:
    # 이 값을 줄여 재기동하면 창이 밀리는 것을 관측할 수 있다 (검증 시나리오 4).
    max-messages: 20

logging:
  level:
    dev.starryeye.memoryagent: DEBUG
```

- [ ] **Step 4: ChatMemoryConfig 작성**

```java
package dev.starryeye.memoryagent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code ChatMemory} 와 {@code ChatMemoryRepository} 빈은 사실 자동설정
 * ({@code ChatMemoryAutoConfiguration})이 공짜로 준다. 둘 다
 * {@code @ConditionalOnMissingBean} 이므로 <b>여기서 직접 정의하면 자동설정이 물러난다.</b>
 *
 * <p>그럼에도 직접 정의하는 이유는 {@code maxMessages} 를 설정으로 바꿔가며
 * "창이 넘치면 무슨 일이 나는지"를 관측하기 위해서다(검증 시나리오 4).
 *
 * <p>{@code ChatMemoryRepository} 는 자동설정이 주는
 * {@code InMemoryChatMemoryRepository} 를 그대로 주입받아 쓴다 — 저장소까지 직접 만들 이유는 없다.
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository,
                                 @Value("${chat.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }
}
```

- [ ] **Step 5: ChatClientConfig 작성**

```java
package dev.starryeye.memoryagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            당신은 친절한 대화 상대입니다.
            사용자가 앞서 한 말을 기억하고 그것을 참고해 답하세요.
            앞선 대화에 없는 내용을 지어내지 마세요. 모르면 모른다고 답하세요.
            답변은 한국어로 간결하게 합니다.
            """;

    /**
     * {@code MessageChatMemoryAdvisor} 를 기본 어드바이저로 붙인다.
     * 이 어드바이저가 요청 전에는 저장된 대화를 프롬프트에 끼워 넣고,
     * 응답 후에는 새 메시지를 저장한다.
     *
     * <p>어드바이저만 붙여서는 부족하다 — <b>어느 대화인지</b>를 요청마다
     * {@code ChatMemory.CONVERSATION_ID} 파라미터로 알려줘야 한다
     * ({@link ChatController} 참고). 그 파라미터가 없으면 모든 요청이
     * 같은 기본 대화로 섞인다.
     */
    @Bean
    public ChatClient memoryChatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
```

- [ ] **Step 6: ChatController 작성**

```java
package dev.starryeye.memoryagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * {@code conversationId} 를 <b>클라이언트가 보낸다.</b> 이 practice 는 사용자가
     * 한 명이라고 가정하므로 서버가 "누구인지"로 대화를 가를 수 없다.
     *
     * <p>이건 의도적으로 비워둔 자리다. 후속 practice 에서 인증과 합칠 때
     * 이 자리에 {@code Authentication.getName()} 이 들어가면 그대로 사용자별 격리가 된다.
     * 지금 이대로는 남의 대화 ID 를 넣으면 그대로 읽힌다 — 그것이 다음 단계의 출발점이다.
     *
     * <p>SSE 가 아니라 {@code text/plain} 이다. SSE 는 Flux 원소마다 프레임을 붙이는데
     * {@code .content()} 는 토큰 단위로 방출하므로 읽을 수 없는 출력이 된다.
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(@RequestParam String conversationId, @RequestBody String message) {
        log.debug("질문 수신 (conversationId={})", conversationId);

        return chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }
}
```

- [ ] **Step 7: MessageView 와 ConversationController 작성**

`MessageView.java`:

```java
package dev.starryeye.memoryagent;

/**
 * 저장소에 쌓인 메시지를 그대로 보여주기 위한 응답 타입.
 * "기억한다"가 마법이 아니라 이 목록이라는 것을 드러내는 것이 목적이다.
 */
public record MessageView(String role, String text) {
}
```

`ConversationController.java`:

```java
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
```

- [ ] **Step 8: static/index.html 작성**

UI 연습이 아니다. 프레임워크·빌드도구 없이 파일 한 장이다.

```html
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <title>chat-memory</title>
    <style>
        body { font-family: system-ui, sans-serif; max-width: 760px; margin: 40px auto; padding: 0 16px; }
        label { display: block; margin-top: 12px; font-size: 13px; color: #555; }
        input { width: 100%; padding: 8px; font-size: 15px; box-sizing: border-box; }
        .row { display: flex; gap: 8px; align-items: flex-end; }
        .row > div { flex: 1; }
        button { padding: 8px 14px; font-size: 14px; white-space: nowrap; }
        pre { background: #f4f4f4; padding: 12px; white-space: pre-wrap; min-height: 4em; }
    </style>
</head>
<body>
<h1>chat-memory</h1>
<p>같은 대화 ID로 이어서 물으면 기억합니다. ID를 바꾸면 기억하지 못합니다.</p>

<div class="row">
    <div>
        <label for="cid">대화 ID</label>
        <input id="cid" value="alpha">
    </div>
    <button id="newCid">새 대화</button>
    <button id="dump">저장 내용 보기</button>
    <button id="clear">이 대화 비우기</button>
</div>

<label for="q">질문</label>
<input id="q" placeholder="예: 내 이름은 스타리야" autofocus>
<button id="send">보내기</button>

<pre id="out"></pre>

<script>
    const out = document.getElementById('out');
    const cid = document.getElementById('cid');
    const q = document.getElementById('q');
    const send = document.getElementById('send');

    document.getElementById('newCid').addEventListener('click', () => {
        cid.value = 'c-' + Math.random().toString(36).slice(2, 8);
        out.textContent = '새 대화 ID: ' + cid.value;
    });

    document.getElementById('dump').addEventListener('click', async () => {
        const res = await fetch('/api/conversations/' + encodeURIComponent(cid.value));
        out.textContent = JSON.stringify(await res.json(), null, 2);
    });

    document.getElementById('clear').addEventListener('click', async () => {
        await fetch('/api/conversations/' + encodeURIComponent(cid.value), {method: 'DELETE'});
        out.textContent = '비웠습니다: ' + cid.value;
    });

    async function ask() {
        const message = q.value.trim();
        if (!message) return;
        send.disabled = true;
        out.textContent = '생각 중... (로컬 모델은 수십 초 걸립니다)';
        try {
            const res = await fetch('/api/chat?conversationId=' + encodeURIComponent(cid.value),
                    {method: 'POST', body: message});
            if (!res.ok) {
                out.textContent = '오류: HTTP ' + res.status;
                return;
            }
            out.textContent = '';
            const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
            for (;;) {
                const {value, done} = await reader.read();
                if (done) break;
                out.textContent += value;
            }
        } catch (e) {
            out.textContent = '오류: ' + e;
        } finally {
            send.disabled = false;
        }
    }

    send.addEventListener('click', ask);
    q.addEventListener('keydown', e => { if (e.key === 'Enter') ask(); });
</script>
</body>
</html>
```

- [ ] **Step 9: 실패하는 테스트 작성 — ChatMemoryConfigTest**

`src/test/java/dev/starryeye/memoryagent/ChatMemoryConfigTest.java`:

```java
package dev.starryeye.memoryagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatMemory 자체의 동작을 LLM 없이 검증한다.
 * 이 practice 의 주제(이어짐 / 격리 / 창 넘침)가 전부 여기서 재현된다.
 */
class ChatMemoryConfigTest {

    private ChatMemory chatMemoryWithMaxMessages(int maxMessages) {
        return new ChatMemoryConfig().chatMemory(new InMemoryChatMemoryRepository(), maxMessages);
    }

    @Test
    void 같은_대화_ID_로_넣으면_그대로_나온다() {
        ChatMemory chatMemory = chatMemoryWithMaxMessages(20);

        chatMemory.add("alpha", List.of(new UserMessage("내 이름은 스타리야")));
        chatMemory.add("alpha", List.of(new AssistantMessage("반가워요 스타리님")));

        List<Message> messages = chatMemory.get("alpha");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText()).isEqualTo("내 이름은 스타리야");
        assertThat(messages.get(1).getText()).isEqualTo("반가워요 스타리님");
    }

    /** 이 practice 의 핵심. 대화 ID 가 곧 격리 경계다. */
    @Test
    void 다른_대화_ID_끼리는_섞이지_않는다() {
        ChatMemory chatMemory = chatMemoryWithMaxMessages(20);

        chatMemory.add("alpha", List.of(new UserMessage("내 이름은 스타리야")));

        assertThat(chatMemory.get("beta")).isEmpty();
        assertThat(chatMemory.get("alpha")).hasSize(1);
    }

    @Test
    void 창을_넘기면_오래된_것부터_밀려난다() {
        ChatMemory chatMemory = chatMemoryWithMaxMessages(2);

        chatMemory.add("alpha", List.of(new UserMessage("첫번째")));
        chatMemory.add("alpha", List.of(new UserMessage("두번째")));
        chatMemory.add("alpha", List.of(new UserMessage("세번째")));

        List<Message> messages = chatMemory.get("alpha");

        assertThat(messages).hasSize(2);
        assertThat(messages).extracting(Message::getText)
                .containsExactly("두번째", "세번째")
                .doesNotContain("첫번째");
    }

    @Test
    void 비우면_사라진다() {
        ChatMemory chatMemory = chatMemoryWithMaxMessages(20);
        chatMemory.add("alpha", List.of(new UserMessage("내 이름은 스타리야")));

        chatMemory.clear("alpha");

        assertThat(chatMemory.get("alpha")).isEmpty();
    }
}
```

- [ ] **Step 10: 테스트를 실행해 실패를 확인**

```bash
cd practice/chat-memory/memory-agent && ./gradlew test --tests '*ChatMemoryConfigTest'
```

Expected: 이 시점에는 컴파일은 되고 **통과**해야 정상이다 (Step 4 에서 이미 구현했으므로).

**빨간불을 실제로 확인하려면** `ChatMemoryConfig.chatMemory(...)` 의 `.maxMessages(maxMessages)`
를 잠시 `.maxMessages(1000)` 으로 바꾸고 다시 실행한다.

Expected (그 상태): `창을_넘기면_오래된_것부터_밀려난다` 가 **실패**한다.
이것이 `maxMessages` 설정이 실제로 반영된다는 증거다. 확인 후 되돌린다.

- [ ] **Step 11: ConversationControllerTest 작성**

```java
package dev.starryeye.memoryagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장소 조회·삭제 경로는 LLM 을 부르지 않으므로 통합 테스트가 가능하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ChatMemory chatMemory;

	@BeforeEach
	void seed() {
		chatMemory.clear("alpha");
		chatMemory.clear("beta");
		chatMemory.add("alpha", List.of(
				new UserMessage("내 이름은 스타리야"),
				new AssistantMessage("반가워요 스타리님")));
	}

	@Test
	void 대화_내용을_역할과_함께_보여준다() throws Exception {
		mockMvc.perform(get("/api/conversations/alpha"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].role").value("user"))
				.andExpect(jsonPath("$[0].text").value("내 이름은 스타리야"))
				.andExpect(jsonPath("$[1].role").value("assistant"));
	}

	@Test
	void 없는_대화는_빈_목록이다() throws Exception {
		mockMvc.perform(get("/api/conversations/beta"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void 대화_목록에_저장된_ID_가_보인다() throws Exception {
		mockMvc.perform(get("/api/conversations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("alpha")));
	}

	@Test
	void 비우면_204_이고_내용이_사라진다() throws Exception {
		mockMvc.perform(delete("/api/conversations/alpha"))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/conversations/alpha"))
				.andExpect(jsonPath("$.length()").value(0));
	}
}
```

> `$[0].role` 의 기대값이 `"user"` / `"assistant"` 인 것은 **확인된 사실이다.**
> `MessageType` 의 정의가 `USER("user")`, `ASSISTANT("assistant")`, `SYSTEM("system")`,
> `TOOL("tool")` 이고 `getValue()` 가 그 문자열을 돌려준다 (소스로 확인).
> 그래도 실제 값이 다르게 나오면 **테스트를 실측값에 맞추고 리포트에 적는다** —
> 구현을 비틀어 테스트를 맞추지 않는다.

- [ ] **Step 12: MemoryAgentApplicationTests 작성**

```java
package dev.starryeye.memoryagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MemoryAgentApplicationTests {

	@Autowired
	ChatMemory chatMemory;

	@Autowired
	ChatMemoryRepository chatMemoryRepository;

	@Autowired
	ChatClient chatClient;

	@Test
	void contextLoads() {
	}

	/**
	 * ChatMemory 자동설정({@code ChatMemoryAutoConfiguration})은
	 * {@code @ConditionalOnMissingBean} 이므로, 우리가 정의한 빈이 이겨야 한다.
	 * 자동설정 것이 잡히면 maxMessages 설정이 무시된다.
	 */
	@Test
	void 내가_정의한_ChatMemory_빈이_쓰인다() {
		assertThat(chatMemory).isInstanceOf(MessageWindowChatMemory.class);
	}

	@Test
	void 저장소는_자동설정이_준_것을_그대로_쓴다() {
		assertThat(chatMemoryRepository).isNotNull();
	}

	@Test
	void ChatClient_빈이_만들어진다() {
		assertThat(chatClient).isNotNull();
	}
}
```

- [ ] **Step 13: 전체 테스트 실행**

```bash
cd practice/chat-memory/memory-agent && ./gradlew test
```

Expected: 12개 테스트 모두 PASS (ChatMemoryConfigTest 4, ConversationControllerTest 4, MemoryAgentApplicationTests 4).

Ollama 가 떠 있지 않아도 통과해야 한다 — LLM 을 실제로 부르는 테스트가 없기 때문이다.
`contextLoads` 가 실패하면 `spring.ai.model.*` 의 `none` 설정을 먼저 본다.

- [ ] **Step 14: 커밋**

```bash
git add practice/chat-memory/memory-agent
git commit -m "feat: chat-memory memory-agent — ChatMemory 배선과 저장소 조회"
```

---

## Task 2: 스트리밍 저장 시점 실측 + 실행 스크립트

**Files:**
- Create: `practice/chat-memory/run.sh`
- Create: `practice/chat-memory/stop.sh`
- Create: `practice/chat-memory/.gitignore`

**Interfaces:**
- Consumes: Task 1 의 `memory-agent`
- Produces: `./run.sh` / `./stop.sh`, 그리고 **스트리밍 저장 시점에 대한 관측 기록**

- [ ] **Step 1: .gitignore 작성**

```
logs/
```

- [ ] **Step 2: run.sh 작성**

```bash
#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p logs

if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q '"21'; then
  if [ -d "$HOME/.sdkman/candidates/java/current" ]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
  fi
fi
echo "JAVA_HOME=${JAVA_HOME:-(미설정)}"

if ! curl -sf http://localhost:11434/api/tags > /dev/null 2>&1; then
  echo "ollama 를 시작합니다..."
  ( nohup ollama serve > logs/ollama.log 2>&1 < /dev/null & disown 2>/dev/null || true )
  for _ in $(seq 1 30); do
    curl -sf http://localhost:11434/api/tags > /dev/null 2>&1 && break
    sleep 1
  done
fi
if ! ollama list 2>/dev/null | grep -q 'qwen3:8b'; then
  echo "qwen3:8b 모델을 내려받습니다 (시간이 걸립니다)..."
  ollama pull qwen3:8b
fi

if lsof -ti tcp:8120 > /dev/null 2>&1; then
  echo "  [건너뜀] memory-agent — 포트 8120 이 이미 사용 중입니다"
else
  echo "  [기동] memory-agent (:8120)"
  # < /dev/null 과 disown 이 없으면 이 스크립트가 호출자를 붙잡는다.
  ( cd memory-agent && nohup ./gradlew bootRun -q > "../logs/memory-agent.log" 2>&1 < /dev/null & disown 2>/dev/null || true )
fi

for _ in $(seq 1 90); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8120/)" != "000" ]; then
    echo "  [준비됨] memory-agent"
    break
  fi
  sleep 1
done

cat <<'EOF'

준비되었습니다.

  브라우저에서 http://localhost:8120/ 을 엽니다.

  같은 대화 ID로 이어서 물어보세요:
    1) 내 이름은 스타리야
    2) 내 이름 뭐야?          → 기억합니다
  "새 대화" 를 누르고 2번을 다시 물어보세요 → 기억하지 못합니다.

  저장된 내용 확인:
    curl -s http://localhost:8120/api/conversations/alpha | python3 -m json.tool

  종료: ./stop.sh
EOF
```

`chmod +x run.sh` 를 잊지 말 것.

- [ ] **Step 3: stop.sh 작성**

```bash
#!/usr/bin/env bash
set -uo pipefail

cd "$(dirname "$0")"

stopped=""
pids=$(lsof -ti tcp:8120 2>/dev/null || true)
if [ -n "$pids" ]; then
  echo "포트 8120 종료: $pids"
  # shellcheck disable=SC2086
  kill $pids 2>/dev/null || true
  stopped="yes"
fi

# gradle bootRun 자식 프로세스가 늦게 죽어 포트를 붙잡고 있는 경우가 있다.
if [ -n "$stopped" ]; then
  sleep 3
  survivors=$(lsof -ti tcp:8120 2>/dev/null || true)
  if [ -n "$survivors" ]; then
    echo "  남은 프로세스 강제 종료: $survivors"
    # shellcheck disable=SC2086
    kill -9 $survivors 2>/dev/null || true
  fi
fi

if [ "${1:-}" = "--ollama" ]; then
  pkill -f 'ollama serve' 2>/dev/null && echo "ollama 종료" || true
fi

echo "완료."
```

`chmod +x stop.sh` 를 잊지 말 것.

- [ ] **Step 4: 앱을 띄운다**

```bash
cd practice/chat-memory && ./run.sh
```

- [ ] **Step 5: ★ 스트리밍 저장 시점을 실측한다 — 이 태스크의 핵심**

스펙이 미확인 위험으로 지목한 항목이다. `MessageChatMemoryAdvisor` 가 `adviseStream(...)` 을
구현하고 `Scheduler` 를 갖는데, **저장이 언제 일어나는지** 확인되지 않았다.

실험 A — **스트리밍 도중에 조회**:

```bash
# 1) 백그라운드로 질문을 던진다 (응답이 오래 걸린다)
curl -s -N -X POST 'http://localhost:8120/api/chat?conversationId=timing' \
  -d '자기소개를 길게 해줘' > /tmp/stream-out.txt &

# 2) 3초 뒤 저장소를 본다 (아직 스트리밍 중)
sleep 3
echo "--- 스트리밍 도중 ---"
curl -s http://localhost:8120/api/conversations/timing | python3 -m json.tool

# 3) 응답이 끝나길 기다린 뒤 다시 본다
wait
echo "--- 스트리밍 종료 후 ---"
curl -s http://localhost:8120/api/conversations/timing | python3 -m json.tool
```

**관측할 것:** 도중에 조회했을 때 (a) 아무것도 없는지, (b) user 메시지만 있는지,
(c) assistant 메시지가 조각난 채 있는지.

실험 B — **스트리밍을 중간에 끊는다**:

```bash
curl -s -N -X POST 'http://localhost:8120/api/chat?conversationId=aborted' \
  -d '자기소개를 아주 길게 해줘' --max-time 3 || true
sleep 2
echo "--- 중간에 끊은 뒤 ---"
curl -s http://localhost:8120/api/conversations/aborted | python3 -m json.tool
```

**관측할 것:** 반쪽 답변이 저장에 남는지, 아무것도 안 남는지, user 메시지만 남는지.

**두 실험의 실제 출력을 그대로 기록한다. 예측을 적지 않는다.**
결과가 어느 쪽이든 학습 포인트다 — 실무에서 "응답이 끊기면 기억이 어떻게 되나"는 실제로 걸리는 문제다.

- [ ] **Step 6: 검증 시나리오 1~5 실행**

브라우저(`mcp__Claude_Browser__*`)로 `http://localhost:8120/` 을 연다.

| # | 조작 | 기대 |
|---|---|---|
| 1 | 대화 ID `alpha` 로 `내 이름은 스타리야` → `내 이름 뭐야?` | 이름을 답한다 |
| 2 | "새 대화" 누르고 `내 이름 뭐야?` | 모른다고 답한다 |
| 3 | ID 를 `alpha` 로 되돌리고 "저장 내용 보기" | user/assistant 메시지가 순서대로 |
| 4 | `application.yml` 의 `max-messages: 2` 로 바꿔 재기동 후 대화를 길게 | 앞부분이 잘린다 |
| 5 | `alpha` 에서 "이 대화 비우기" 후 `내 이름 뭐야?` | 모른다고 답한다 |

시나리오 4 후에는 `max-messages` 를 **20 으로 되돌린다.**

관측한 답변을 그대로 기록한다. 로컬 모델이라 문장은 매번 다르므로 **의미**가 맞는지 본다.

- [ ] **Step 7: 종료 확인**

```bash
./stop.sh
lsof -ti tcp:8120 || echo "포트 8120 해제됨"
```

- [ ] **Step 8: 커밋**

```bash
chmod +x run.sh stop.sh
git add practice/chat-memory/run.sh practice/chat-memory/stop.sh practice/chat-memory/.gitignore
git commit -m "feat: chat-memory 실행 스크립트"
```

---

## Task 3: README + 루트 README 등록

**Files:**
- Create: `practice/chat-memory/README.md`
- Modify: `README.md` (루트 — practice 목록에 **추가만**)

**Interfaces:**
- Consumes: Task 1·2 의 산출물과 **관측 결과**
- Produces: 관측값이 담긴 README

- [ ] **Step 1: README 작성**

Task 2 에서 **실제로 관측한 값만** 적는다. 예측값을 적지 않는다.

포함할 절:

1. **한 줄 소개** — 앞선 네 practice 의 에이전트는 매 요청이 독립적이었다는 점에서 출발.
   MCP 도 인증도 없이 대화 기억만 다룬다는 것
2. **학습 목표** — 스펙의 4가지 목표와 각각의 확인 방법
3. **세 조각이 어떻게 맞물리나** — 아래 표와 흐름 설명

   | 조각 | 역할 |
   |---|---|
   | `ChatMemoryRepository` | 실제 저장. 기본 구현은 `InMemoryChatMemoryRepository` |
   | `ChatMemory` | 저장소 위의 정책. `MessageWindowChatMemory` 는 `maxMessages` 만큼만 유지 |
   | `MessageChatMemoryAdvisor` | 요청 전 대화를 프롬프트에 끼워 넣고, 응답 후 저장 |
   | `ChatMemory.CONVERSATION_ID` | 어느 대화인지 지정하는 요청 파라미터 (`"chat_memory_conversation_id"`) |

   어드바이저만 붙여서는 부족하고 요청마다 대화 ID 를 줘야 한다는 점을 명시한다

4. **자동설정과의 관계** — `ChatMemory`·`ChatMemoryRepository` 는 자동설정이 공짜로 주지만
   `@ConditionalOnMissingBean` 이라 직접 정의하면 물러난다는 것. 여기서는 `maxMessages` 통제를
   위해 `ChatMemory` 만 직접 정의하고 저장소는 자동설정 것을 쓴다는 것
5. **실행** — `./run.sh`, `http://localhost:8120/`
6. **직접 해보기** — 검증 시나리오 1~5 를 따라할 수 있는 절차와 관측된 결과
7. **스트리밍 저장 시점** — Task 2 Step 5 의 두 실험(도중 조회 / 중간 끊기)과 **관측된 출력**
8. **학습 포인트** — 최소한 아래를 포함하고 구현 중 실제로 겪은 것을 추가한다:
   - **대화를 가르는 키가 곧 격리 경계다.** 지금은 클라이언트가 `conversationId` 를 보내므로
     남의 대화 ID 를 넣으면 그대로 읽힌다 — 사용자 한 명 가정이라 성립하는 것이다
   - `MessageWindowChatMemory` 는 오래된 메시지를 밀어낸다. 창 크기가 곧 기억의 길이다
   - 저장소에 쌓이는 것은 결국 메시지 목록이다 — 조회 엔드포인트로 확인할 수 있다
   - `findConversationIds()` 는 `ChatMemoryRepository` 에만 있고 `ChatMemory` 에는 없다
   - Task 2 Step 5 에서 관측한 스트리밍 저장 시점
9. **트러블슈팅** — 표로
10. **다음 practice 로** — `conversationId` 를 클라이언트가 정하게 두면 안 되는 이유,
    `Authentication.getName()` 에서 파생시키면 격리가 되는 것,
    그리고 **MCP 툴 호출 결과가 대화 기록에 남는다**는 새 문제
    (A 가 조회한 데이터가 B 의 컨텍스트로 새면 인증이 무의미해진다)

- [ ] **Step 2: 루트 README 에 항목 추가**

루트 `README.md` 의 practice 표에 행을 **하나 추가**한다. 기존 4개 행은 그대로 둔다.

```
| [`practice/chat-memory/`](practice/chat-memory) | ChatMemory · MessageChatMemoryAdvisor · conversationId 로 대화를 기억하는 예제. MCP·인증 없이 메모리만 다룬다. 사용자 1명 가정. `memory-agent` :8120 |
```

- [ ] **Step 3: 무변경 확인**

```bash
git status --short
git diff --stat HEAD~2..HEAD -- practice/agent-mcp practice/agent-mcps \
    practice/mcp-security-authn-community practice/mcp-security-authn-official
```

두 번째 명령의 출력이 **비어 있어야 한다.**

MCP·Security 의존성이 안 들어갔는지도 확인한다:

```bash
grep -nE 'mcp|security|springaicommunity' practice/chat-memory/memory-agent/build.gradle \
  || echo "✔ MCP·Security 의존성 없음"
```

- [ ] **Step 4: 커밋**

```bash
git add practice/chat-memory/README.md README.md
git commit -m "docs: chat-memory README — 관측된 검증 결과와 다음 practice 로의 연결"
```

---

## 완료 조건

- [ ] `./run.sh` 로 앱이 뜬다
- [ ] 같은 `conversationId` 로 이어서 물으면 앞 내용을 기억한다
- [ ] 다른 `conversationId` 로는 기억하지 못한다
- [ ] `GET /api/conversations/{id}` 가 저장된 메시지를 역할과 함께 보여준다
- [ ] `max-messages` 를 줄이면 앞부분이 밀려난다 (커밋 상태는 20)
- [ ] `DELETE` 후 기억이 사라진다
- [ ] **스트리밍 저장 시점 두 실험의 관측 결과가 README 에 적혀 있다**
- [ ] `./gradlew test` 12개 통과 (ollama 없이도)
- [ ] `build.gradle` 에 MCP·Security 의존성이 없다
- [ ] 앞선 네 practice 무변경
- [ ] 루트 README 에 practice 5개가 나열된다
