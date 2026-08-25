#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p logs

# Java 21 확보 (시스템 기본은 17)
if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q '"21'; then
  if [ -d "$HOME/.sdkman/candidates/java/current" ]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
  fi
fi
echo "JAVA_HOME=${JAVA_HOME:-(미설정)}"

# ollama 준비
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

start() {
  local dir="$1" port="$2"
  if lsof -ti tcp:"$port" > /dev/null 2>&1; then
    echo "  [건너뜀] $dir — 포트 $port 가 이미 사용 중입니다"
    return
  fi
  echo "  [기동] $dir (:$port)"
  # < /dev/null 과 disown 이 없으면 이 스크립트가 호출자를 붙잡는다.
  ( cd "$dir" && nohup ./gradlew bootRun -q > "../logs/$dir.log" 2>&1 < /dev/null & disown 2>/dev/null || true )
}

wait_for() {
  local name="$1" url="$2"
  for _ in $(seq 1 90); do
    if curl -sf -o /dev/null "$url" || [ "$(curl -s -o /dev/null -w '%{http_code}' "$url")" != "000" ]; then
      echo "  [준비됨] $name"
      return 0
    fi
    sleep 1
  done
  echo "  [실패] $name 이 뜨지 않았습니다. logs/$name.log 마지막 20줄:"
  tail -20 "logs/$name.log" || true
  return 1
}

echo "기동 순서: auth-server → shop-mcp-server → shop-agent"
start auth-server 9000
wait_for auth-server http://localhost:9000/.well-known/openid-configuration

start shop-mcp-server 8101
wait_for shop-mcp-server http://localhost:8101/mcp

start shop-agent 8100
wait_for shop-agent http://localhost:8100/

cat <<'EOF'

준비되었습니다.

  브라우저에서 http://localhost:8100/ 을 엽니다.
  로그인: user / password

  토큰 없이 MCP 서버를 직접 찔러보려면:
    curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8101/mcp \
      -H 'Content-Type: application/json' \
      -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
  → 401 이 나와야 합니다.

  종료: ./stop.sh
EOF
