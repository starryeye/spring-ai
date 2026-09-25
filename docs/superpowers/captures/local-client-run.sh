#!/usr/bin/env bash
# local-client 를 --no-browser 로 돌리고, browser 대신 curl 이 login 과 consent 를 한다.
# 사용: official 의 auth-server(:9010)·shop-mcp-server(:8111) 를 띄운 뒤 실행한다.
#   ./local-client-run.sh > 결과.txt
set -uo pipefail

AS=${AS:-http://localhost:9010}
LOGIN_USERNAME=${LOGIN_USERNAME:-user}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
CLIENT_DIR=${CLIENT_DIR:-$(cd "$(dirname "$0")/../../../practice/mcp-security-authn-official/local-client" && pwd)}

JAR=$(mktemp)
OUT=$(mktemp)
trap 'rm -f "$JAR" "$OUT"' EXIT

( cd "$CLIENT_DIR" && ./gradlew -q run --args="--no-browser" > "$OUT" 2>&1 ) &
CLIENT_PID=$!

URL=""
for _ in $(seq 1 180); do
  URL=$(grep -o 'http://[^ ]*/oauth2/authorize?[^ ]*' "$OUT" | head -1)
  [ -n "$URL" ] && break
  kill -0 "$CLIENT_PID" 2>/dev/null || break
  sleep 1
done
if [ -z "$URL" ]; then
  echo "[오류] authorization 주소가 나오지 않았다" >&2
  cat "$OUT" >&2
  exit 1
fi

# browser 대신: login → authorization 주소 → consent 제출 → loopback callback 으로 redirect 따라가기
FORM=$(curl -s -c "$JAR" -b "$JAR" "$AS/login")
CSRF=$(printf '%s' "$FORM" | grep -o '<input[^>]*name="_csrf"[^>]*>' | head -1 | grep -o 'value="[^"]*"' | sed 's/^value="//;s/"$//')
curl -s -o /dev/null -c "$JAR" -b "$JAR" -X POST "$AS/login" \
  --data-urlencode "username=$LOGIN_USERNAME" --data-urlencode "password=$LOGIN_PASSWORD" --data-urlencode "_csrf=$CSRF"
PAGE=$(curl -s -c "$JAR" -b "$JAR" "$URL")
STATE=$(printf '%s' "$PAGE" | grep -o 'name="state" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//')
curl -s -o /dev/null -L -c "$JAR" -b "$JAR" -X POST "$AS/oauth2/authorize" \
  --data-urlencode 'client_id=local-mcp-client' --data-urlencode "state=$STATE" --data-urlencode 'scope=profile'

wait "$CLIENT_PID"
STATUS=$?
printf '# local-client 실행 — %s (local-client-run.sh, browser 대신 curl)\n\n' "$(date +%F)"
sed -E 's/(code_challenge=)[^&]{12}[^&]*/\1.../; s/(state=)[^&]{6}[^&]*/\1.../' "$OUT"
exit "$STATUS"
