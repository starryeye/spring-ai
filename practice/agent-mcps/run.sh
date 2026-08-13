#!/usr/bin/env bash
#
# MCP 서버 2개 + 에이전트를 한 번에 띄운다.
#
#   ./run.sh                        ollama + safe (기본)
#   ./run.sh ollama none            필터 없음 — cancelOrder 노출
#   ./run.sh ollama product-only    order 서버 툴 전부 차단
#   ./run.sh openai safe            OpenAI 로 (키 필요)
#
# 로그는 logs/ 아래. 종료는 ./stop.sh
#
set -euo pipefail

cd "$(dirname "$0")"

PROFILE="${1:-ollama}"
FILTER="${2:-safe}"
OLLAMA_MODEL="qwen3:8b"
LOG_DIR="logs"
mkdir -p "$LOG_DIR"

info()  { printf '\033[1;34m▸\033[0m %s\n' "$*"; }
ok()    { printf '\033[1;32m✔\033[0m %s\n' "$*"; }
warn()  { printf '\033[1;33m!\033[0m %s\n' "$*"; }
die()   { printf '\033[1;31m✘\033[0m %s\n' "$*" >&2; exit 1; }

case "$PROFILE" in ollama|openai|anthropic) ;; *) die "알 수 없는 프로파일: $PROFILE" ;; esac
case "$FILTER" in none|safe|product-only) ;; *) die "알 수 없는 필터 모드: $FILTER (none | safe | product-only)" ;; esac

find_java21() {
  if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
    echo "$JAVA_HOME"; return
  fi
  for candidate in "$HOME"/.sdkman/candidates/java/21*/ ; do
    [ -x "$candidate/bin/java" ] && { echo "${candidate%/}"; return; }
  done
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    /usr/libexec/java_home -v 21 2>/dev/null && return
  fi
  return 1
}

JAVA_HOME="$(find_java21)" || die "Java 21 을 찾지 못했다."
export JAVA_HOME
ok "Java 21: $JAVA_HOME"

if [ "$PROFILE" = "ollama" ]; then
  command -v ollama >/dev/null || die "ollama 가 없다. 'brew install ollama' 후 다시 실행할 것."
  if ! curl -sf http://localhost:11434/api/version >/dev/null 2>&1; then
    info "ollama 서버 기동 중..."
    nohup ollama serve > "$LOG_DIR/ollama.log" 2>&1 < /dev/null &
    disown 2>/dev/null || true
    for _ in $(seq 1 30); do
      curl -sf http://localhost:11434/api/version >/dev/null 2>&1 && break
      sleep 1
    done
    curl -sf http://localhost:11434/api/version >/dev/null 2>&1 || die "ollama 기동 실패"
  fi
  ok "ollama 응답 중"
  if ! ollama list 2>/dev/null | grep -q "^${OLLAMA_MODEL%%:*}"; then
    warn "모델 $OLLAMA_MODEL 이 없다. 내려받는 중..."
    ollama pull "$OLLAMA_MODEL" || die "모델 다운로드 실패"
  fi
  ok "모델 준비됨: $OLLAMA_MODEL"
fi

start_app() {
  local dir="$1" port="$2" ready="$3"; shift 3
  local log="$LOG_DIR/$dir.log"

  if lsof -ti:"$port" >/dev/null 2>&1; then
    warn "$dir: 포트 $port 사용 중이라 건너뛴다 (./stop.sh 로 먼저 정리할 것)."
    return 1
  fi

  info "$dir 기동 중 (:$port)..."
  ( cd "$dir" && nohup ./gradlew bootRun -q "$@" > "../$log" 2>&1 < /dev/null & disown 2>/dev/null || true )

  for _ in $(seq 1 90); do
    grep -q "$ready" "$log" 2>/dev/null && { ok "$dir 준비됨 (:$port)"; return 0; }
    grep -qE 'APPLICATION FAILED|BUILD FAILED' "$log" 2>/dev/null && {
      printf '\n'; tail -20 "$log"; die "$dir 기동 실패 — $log 확인"
    }
    sleep 2
  done
  die "$dir 기동 시간 초과. $log 확인."
}

start_app product-mcp-server 8091 'Started ProductMcpServerApplication' || true
start_app order-mcp-server   8092 'Started OrderMcpServerApplication' || true

# shop-agent 는 반드시 이번에 이 필터 모드로 새로 떠야 한다 — 이미 떠 있는 걸 건너뛰면
# 아래 배너의 "필터: $FILTER" 가 실제로 실행 중인(어쩌면 다른) 모드와 어긋나 거짓말을 하게 된다.
if ! start_app shop-agent 8090 'Started ShopAgentApplication' \
          --args="--spring.profiles.active=$PROFILE --shop.tool-filter=$FILTER"; then
  die "shop-agent 가 이미 실행 중이라 필터 '$FILTER' 를 적용하지 못했다. ./stop.sh 로 먼저 정리한 뒤 다시 실행할 것."
fi

cat <<EOF

$(ok "전부 기동됨 — 프로파일: $PROFILE / 필터: $FILTER")

  product-mcp-server http://localhost:8091
  order-mcp-server   http://localhost:8092
  shop-agent         http://localhost:8090

물어보기:

  curl -N -X POST http://localhost:8090/api/chat \\
    -H 'Content-Type: text/plain' \\
    -d '홍길동 주문 보여줘'

툴 호출 확인:

  grep 호출 $LOG_DIR/order-mcp-server.log $LOG_DIR/product-mcp-server.log

종료:

  ./stop.sh
EOF
