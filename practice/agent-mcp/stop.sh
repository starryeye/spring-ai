#!/usr/bin/env bash
#
# run.sh 로 띄운 세 서버를 종료한다.
# ollama 는 다른 용도로도 쓰일 수 있으므로 기본적으로 건드리지 않는다.
#
#   ./stop.sh              세 서버만 종료
#   ./stop.sh --ollama     ollama 서버까지 종료
#
set -uo pipefail

cd "$(dirname "$0")"

ok()   { printf '\033[1;32m✔\033[0m %s\n' "$*"; }
info() { printf '\033[1;34m▸\033[0m %s\n' "$*"; }

stopped=0
for port in 8080 8081 8082; do
  pids="$(lsof -ti:"$port" 2>/dev/null)"
  if [ -n "$pids" ]; then
    info "포트 $port 종료 중..."
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null
    stopped=1
  fi
done

if [ "$stopped" = "1" ]; then
  sleep 3
  for port in 8080 8081 8082; do
    pids="$(lsof -ti:"$port" 2>/dev/null)"
    # shellcheck disable=SC2086
    [ -n "$pids" ] && kill -9 $pids 2>/dev/null
  done
fi

# bootRun 을 감싼 Gradle 데몬 래퍼가 남는 경우가 있다.
pkill -f 'dev.starryeye.product' 2>/dev/null

ok "세 서버 종료됨"

if [ "${1:-}" = "--ollama" ]; then
  pkill -f 'ollama serve' 2>/dev/null && ok "ollama 종료됨" || info "실행 중인 ollama 없음"
else
  curl -sf http://localhost:11434/api/version >/dev/null 2>&1 \
    && info "ollama 는 계속 실행 중이다 (종료하려면 ./stop.sh --ollama)"
fi
