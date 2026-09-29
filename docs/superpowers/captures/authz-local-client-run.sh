#!/usr/bin/env bash
# mcp-security-authz 의 local-client 를 --no-browser 로 돌리고, browser 대신 curl 이 login 과 consent 를 한다.
# 처음 authorization 과 step-up authorization 두 번을 처리한다.
# 사용: practice/mcp-security-authz 의 auth-server(:9030)·shop-mcp-server(:8141) 를 띄운 뒤 실행한다.
#   ./authz-local-client-run.sh > 결과.txt
set -uo pipefail

AS=${AS:-http://localhost:9030}
LOGIN_USERNAME=${LOGIN_USERNAME:-user}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
CLIENT_DIR=${CLIENT_DIR:-$(cd "$(dirname "$0")/../../../practice/mcp-security-authz/local-client" && pwd)}

JAR=$(mktemp)
OUT=$(mktemp)
trap 'rm -f "$JAR" "$OUT"' EXIT

( cd "$CLIENT_DIR" && ./gradlew -q run --args="--no-browser" > "$OUT" 2>&1 ) &
CLIENT_PID=$!

# n 번째 authorization 주소가 출력에 나올 때까지 기다린다.
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

# 주소를 열어 consent 화면의 state 를 읽고, 주어진 scope 를 체크해 제출한다. redirect 는 loopback callback 까지 따라간다.
consent() {
  local url="$1" page state args
  shift
  page=$(curl -s -c "$JAR" -b "$JAR" "$url")
  state=$(printf '%s' "$page" | grep -o 'name="state" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//')
  if [ -z "$state" ]; then
    echo "[오류] consent 화면의 state 를 찾지 못했다" >&2
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

# public client 는 consent 를 저장하지 않아 step-up 에서도 두 scope 를 모두 묻는다. 둘 다 체크한다.
wait_url 2 || { echo "[오류] step-up authorization 주소가 나오지 않았다" >&2; cat "$OUT" >&2; exit 1; }
consent "$URL" products:read products:write

wait "$CLIENT_PID"
STATUS=$?
printf '# mcp-security-authz local-client 실행 — %s (authz-local-client-run.sh, browser 대신 curl)\n\n' "$(date +%F)"
sed -E 's/(code_challenge=)[^&]{12}[^&]*/\1.../; s/(state=)[^&]{6}[^&]*/\1.../' "$OUT"
exit "$STATUS"
