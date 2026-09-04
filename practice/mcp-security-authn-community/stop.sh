#!/usr/bin/env bash
set -uo pipefail

cd "$(dirname "$0")"

stopped=0
for port in 8100 8101 9000; do
  pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
  if [ -n "$pids" ]; then
    echo "포트 $port 종료: $pids"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
    stopped=1
  fi
done

# 위 kill(SIGTERM) 만으로 프로세스가 안 죽고 살아남는 경우가 실측됐다
# (특히 :8101, gradlew bootRun 자식 프로세스). 살려두면 run.sh 는 "포트가 이미
# 사용 중"이라 판단해 [건너뜀] 만 찍고 새로 안 띄우면서도, 그 오래된(설정이
# 바뀌었을 수도 있는) 서버에 뜬 것으로 착각해 [준비됨] 을 출력한다 —
# stop→설정 수정→run 이 조용히 낡은 서버를 계속 쓰게 되는 조합. 그래서 잠깐
# 기다린 뒤 살아남은 프로세스만 SIGKILL 로 확실히 정리한다.
if [ "$stopped" = "1" ]; then
  sleep 3
  for port in 8100 8101 9000; do
    pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
      echo "포트 $port 강제 종료: $pids"
      # shellcheck disable=SC2086
      kill -9 $pids 2>/dev/null || true
    fi
  done
fi

if [ "${1:-}" = "--ollama" ]; then
  pkill -f 'ollama serve' 2>/dev/null && echo "ollama 종료" || true
fi

echo "완료."
