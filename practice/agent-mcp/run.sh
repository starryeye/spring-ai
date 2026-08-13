#!/usr/bin/env bash
#
# 세 서버(+ ollama 프로파일이면 로컬 LLM)를 한 번에 띄운다.
#
#   ./run.sh              ollama 프로파일 (기본, API 키 불필요)
#   ./run.sh openai       OpenAI  — 키 필요
#   ./run.sh anthropic    Claude  — 키 필요
#
# 로그는 logs/ 아래에 쌓인다. 종료는 ./stop.sh
#
set -euo pipefail

cd "$(dirname "$0")"

PROFILE="${1:-ollama}"
OLLAMA_MODEL="qwen3:8b"
LOG_DIR="logs"
mkdir -p "$LOG_DIR"

info()  { printf '\033[1;34m▸\033[0m %s\n' "$*"; }
ok()    { printf '\033[1;32m✔\033[0m %s\n' "$*"; }
warn()  { printf '\033[1;33m!\033[0m %s\n' "$*"; }
die()   { printf '\033[1;31m✘\033[0m %s\n' "$*" >&2; exit 1; }

case "$PROFILE" in
  ollama|openai|anthropic) ;;
  *) die "알 수 없는 프로파일: $PROFILE (ollama | openai | anthropic)" ;;
esac

# ---------------------------------------------------------------- Java 21
# Gradle toolchain 이 21 을 요구한다. 시스템 기본이 다른 버전일 수 있으므로 직접 찾는다.
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

JAVA_HOME="$(find_java21)" || die "Java 21 을 찾지 못했다. JAVA_HOME 을 직접 지정할 것."
export JAVA_HOME
ok "Java 21: $JAVA_HOME"

# ---------------------------------------------------------------- 사전 점검
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
    curl -sf http://localhost:11434/api/version >/dev/null 2>&1 \
      || die "ollama 기동 실패. $LOG_DIR/ollama.log 확인."
  fi
  ok "ollama 응답 중"

  if ! ollama list 2>/dev/null | grep -q "^${OLLAMA_MODEL%%:*}"; then
    warn "모델 $OLLAMA_MODEL 이 없다. 내려받는 중 (수 GB, 시간이 걸린다)..."
    ollama pull "$OLLAMA_MODEL" || die "모델 다운로드 실패"
  fi
  ok "모델 준비됨: $OLLAMA_MODEL"
else
  # 키는 환경변수 또는 product-agent/secrets.yml 로 줄 수 있다. 여기서는 경고만 한다.
  var="$( [ "$PROFILE" = "openai" ] && echo OPENAI_API_KEY || echo ANTHROPIC_API_KEY )"
  if [ -z "${!var:-}" ] && ! grep -qs "^$var:" product-agent/secrets.yml; then
    warn "$var 가 환경변수에도 product-agent/secrets.yml 에도 없다."
    warn "기동은 되지만 첫 요청에서 인증 오류가 난다."
  fi
fi

# ---------------------------------------------------------------- 기동
# $1 디렉터리, $2 포트, $3 기동완료 로그 패턴, $4.. 추가 인자
start_app() {
  local dir="$1" port="$2" ready="$3"; shift 3
  local log="$LOG_DIR/$dir.log"

  if lsof -ti:"$port" >/dev/null 2>&1; then
    warn "$dir: 포트 $port 이 이미 사용 중이다. 건너뛴다 (./stop.sh 로 먼저 정리할 것)."
    return 0
  fi

  info "$dir 기동 중 (:$port)..."
  ( cd "$dir" && nohup ./gradlew bootRun -q "$@" > "../$log" 2>&1 < /dev/null & disown 2>/dev/null || true )

  for _ in $(seq 1 90); do
    grep -q "$ready" "$log" 2>/dev/null && { ok "$dir 준비됨 (:$port)"; return 0; }
    grep -qE 'APPLICATION FAILED|BUILD FAILED' "$log" 2>/dev/null && {
      printf '\n'; tail -20 "$log"; die "$dir 기동 실패 — 위 로그 확인 ($log)"
    }
    sleep 2
  done
  die "$dir 기동 시간 초과. $log 확인."
}

start_app product-service    8081 'Started ProductServiceApplication'
start_app product-mcp-server 8082 'Started ProductMcpServerApplication'
start_app product-agent      8080 'Started ProductAgentApplication' \
          --args="--spring.profiles.active=$PROFILE"

# ---------------------------------------------------------------- 안내
cat <<EOF

$(ok "전부 기동됨 — 프로파일: $PROFILE")

  product-service    http://localhost:8081
  product-mcp-server http://localhost:8082
  product-agent      http://localhost:8080

물어보기:

  curl -N -X POST http://localhost:8080/api/chat \\
    -H 'Content-Type: text/plain' \\
    -d '노트북 재고 있어?'

툴이 실제로 호출됐는지:

  grep 호출 $LOG_DIR/product-mcp-server.log

종료:

  ./stop.sh
EOF
