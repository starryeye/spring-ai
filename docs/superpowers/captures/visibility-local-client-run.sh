#!/usr/bin/env bash
# mcp-tool-visibility의 local-client를 --no-browser로 돌리고, browser 대신 curl이 login과 consent를 한다.
# 처음 authorization과, checkout이 403을 받은 뒤의 step-up authorization 두 번을 처리한다.
# public client(local-mcp-client)는 consent를 저장하지 않는다. 그래서 step-up에서도
# products:read와 orders:write를 모두 묻는 화면이 나오고, 이 스크립트는 둘 다 체크한다.
# login할 계정은 첫 인자로 받는다. user는 점원이라 tool 7개가, user2는 손님이라 updateStock이 빠진 6개가 보인다.
# 사용: practice/mcp-tool-visibility의 auth-server(:9050)·shop-mcp-server(:8161)를 띄운 뒤 실행한다.
#   ./visibility-local-client-run.sh user > 결과.txt
#   ./visibility-local-client-run.sh user2 > 결과.txt
set -uo pipefail

AS=${AS:-http://localhost:9050}
LOGIN_USERNAME=${1:-${LOGIN_USERNAME:-user}}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
CLIENT_DIR=${CLIENT_DIR:-$(cd "$(dirname "$0")/../../../practice/mcp-tool-visibility/local-client" && pwd)}

JAR=$(mktemp)
OUT=$(mktemp)
trap 'rm -f "$JAR" "$OUT"' EXIT

( cd "$CLIENT_DIR" && ./gradlew -q run --args="--no-browser" > "$OUT" 2>&1 ) &
CLIENT_PID=$!

# n번째 authorization 주소가 출력에 나올 때까지 기다린다.
wait_url() {
  URL=""
  for _ in $(seq 1 360); do
    URL=$(grep -o 'http://[^ ]*/oauth2/authorize?[^ ]*' "$OUT" | sed -n "${1}p")
    [ -n "$URL" ] && return 0
    kill -0 "$CLIENT_PID" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}

# 주소를 열어 consent 화면의 state를 읽고, 주어진 scope를 체크해 제출한다. redirect는 loopback callback까지 따라간다.
consent() {
  local url="$1" page state args
  shift
  page=$(curl -s -c "$JAR" -b "$JAR" "$url")
  state=$(printf '%s' "$page" | grep -o 'name="state" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//')
  if [ -z "$state" ]; then
    echo "[오류] consent 화면의 state를 찾지 못했다" >&2
    kill "$CLIENT_PID" 2>/dev/null
    exit 1
  fi
  args=(--data-urlencode 'client_id=local-mcp-client' --data-urlencode "state=$state")
  for s in "$@"; do args+=(--data-urlencode "scope=$s"); done
  curl -s -o /dev/null -L -c "$JAR" -b "$JAR" -X POST "$AS/oauth2/authorize" "${args[@]}"
}

wait_url 1 || { echo "[오류] 첫 authorization 주소가 나오지 않았다" >&2; cat "$OUT" >&2; exit 1; }
FORM=$(curl -s -c "$JAR" -b "$JAR" "$AS/login")
CSRF=$(printf '%s' "$FORM" | grep -o '<input[^>]*name="_csrf"[^>]*>' | head -1 | grep -o 'value="[^"]*"' | sed 's/^value="//;s/"$//')
curl -s -o /dev/null -c "$JAR" -b "$JAR" -X POST "$AS/login" \
  --data-urlencode "username=$LOGIN_USERNAME" --data-urlencode "password=$LOGIN_PASSWORD" --data-urlencode "_csrf=$CSRF"
consent "$URL" products:read

# public client는 consent를 저장하지 않아 step-up에서도 두 scope를 모두 묻는다. 둘 다 체크한다.
wait_url 2 || { echo "[오류] step-up authorization 주소가 나오지 않았다" >&2; cat "$OUT" >&2; exit 1; }
consent "$URL" products:read orders:write

wait "$CLIENT_PID"
STATUS=$?
printf '# mcp-tool-visibility local-client 실행(계정 %s) — %s (visibility-local-client-run.sh, browser 대신 curl)\n\n' "$LOGIN_USERNAME" "$(date +%F)"
sed -E 's/(code_challenge=)[^&]{12}[^&]*/\1.../; s/(state=)[^&]{6}[^&]*/\1.../' "$OUT"
exit "$STATUS"
