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
