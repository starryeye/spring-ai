# legacy-0.8

Spring AI **0.8.1** 기준으로 작성된 초기 학습 코드다. 2024년 5월 이후 방치되었고,
Spring AI 2.0 기준으로는 **컴파일되지 않는다.** 참고용으로만 남긴다.

주요 비호환:

- `org.springframework.ai.chat.ChatClient` 는 2.0에서 `ChatModel` 로 개명되었고,
  `ChatClient` 라는 이름은 fluent API로 완전히 재설계되었다.
- `chatClient.call(prompt).getResult().getOutput().getContent()`
  → `chatClient.prompt().user(...).call().content()`
- `getContent()` → `getText()`
- 스타터 `spring-ai-openai-spring-boot-starter` → `spring-ai-starter-model-openai`
- `repo.spring.io/milestone` 저장소가 더 이상 필요 없다 (Maven Central GA)

현행 코드는 `practice/agent-mcp/` 를 볼 것.
