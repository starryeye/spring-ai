# QUICKSTART

Spring AI 2.0 에이전트가 MCP 를 통해 평범한 REST 서비스를 호출하는 예제.
**API 키 없이** 로컬 모델로 전 구간을 돌려볼 수 있다.

설계 배경·학습 포인트·트러블슈팅은 [README.md](README.md) 를 볼 것.

---

## 무엇이 도는가

프로세스 3개 + LLM 1개. 각자 역할이 하나씩이다.

```
  당신
    │  POST /api/chat  "노트북 재고 있어?"
    ▼
┌─────────────────┐        ┌──────────┐
│  product-agent  │◀──────▶│   LLM    │  ollama(로컬) / OpenAI / Claude
│      :8080      │        └──────────┘
└────────┬────────┘   "툴을 써야겠다" 고 판단하는 주체는 LLM 이다
         │  MCP (streamable HTTP)
         ▼
┌─────────────────────┐
│  product-mcp-server │  @McpTool 로 툴 2개를 노출할 뿐, LLM 을 부르지 않는다
│        :8082        │    · searchProducts(keyword)
└────────┬────────────┘    · getStock(productId)
         │  일반 HTTP
         ▼
┌─────────────────┐
│ product-service │  Spring AI 를 아예 모르는 평범한 REST 서비스
│      :8081      │  = 이미 있던 시스템을 감싼다는 시나리오
└─────────────────┘
```

핵심은 **product-service 가 AI 를 전혀 모른다**는 점이다.
기존 시스템을 건드리지 않고 MCP 서버로 감싸 에이전트에 물리는 것이 이 예제의 주제다.

## 실행

```bash
./run.sh
```

끝이다. 스크립트가 Java 21 을 찾고, ollama 를 띄우고, 모델이 없으면 받고,
세 서버를 순서대로 기동한 뒤 준비될 때까지 기다린다.

```bash
./run.sh openai       # OpenAI 로 (키 필요)
./run.sh anthropic    # Claude 로 (키 필요)
```

키는 `product-agent/secrets.yml` 에 넣거나 환경변수로 준다.
(`cp product-agent/secrets.yml.example product-agent/secrets.yml`)

## 물어보기

```bash
curl -N -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: text/plain' \
  -d '노트북 재고 있어?'
```

기대 결과 — 답변에 **실제 재고 숫자**(p1 7개, p2 23개)가 들어 있어야 한다.
숫자가 없거나 "확인할 수 없다"고 하면 툴이 호출되지 않은 것이다.

툴이 실제로 불렸는지 확인:

```bash
grep 호출 logs/product-mcp-server.log
# → searchProducts 호출 (keyword=노트북)
```

품절 케이스도 해볼 만하다 (재고 0 인 상품):

```bash
curl -N -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: text/plain' -d '무선 기계식 키보드 살 수 있어?'
```

## 종료

```bash
./stop.sh            # 세 서버만
./stop.sh --ollama   # ollama 까지
```

## 안 될 때

| 증상 | 먼저 볼 곳 |
|---|---|
| 기동 실패 | `logs/<서비스명>.log` — run.sh 가 실패 시 마지막 20줄을 보여준다 |
| 답변에 재고 숫자가 없다 | `logs/product-mcp-server.log` 에 호출 기록이 있는지 |
| 포트가 이미 사용 중 | `./stop.sh` 후 재실행 |
| 로컬 모델이 느리다 | 정상이다. qwen3:8b 기준 수십 초 걸린다 |

로컬 모델은 툴 선택 정확도가 프론티어 모델보다 낮다. 툴을 안 부르는 일이 반복되면
`product-agent/src/main/resources/application-ollama.yml` 의 모델을 바꿔보거나,
키가 있다면 `./run.sh openai` 로 비교해보면 차이가 분명히 보인다.
