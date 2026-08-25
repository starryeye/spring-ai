#!/usr/bin/env bash
set -uo pipefail

cd "$(dirname "$0")"

for port in 8100 8101 9000; do
  pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
  if [ -n "$pids" ]; then
    echo "포트 $port 종료: $pids"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
  fi
done

if [ "${1:-}" = "--ollama" ]; then
  pkill -f 'ollama serve' 2>/dev/null && echo "ollama 종료" || true
fi

echo "완료."
