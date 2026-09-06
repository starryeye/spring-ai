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
