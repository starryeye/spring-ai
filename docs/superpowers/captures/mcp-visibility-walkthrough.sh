#!/usr/bin/env bash
# mcp-tool-visibility practice에서 사용자 역할로 거른 tool 목록과 숨긴 tool 호출을 curl로 한 단계씩 기록한다(V 번호).
# 서버는 session을 만들지 않는다. 그래서 모든 MCP 요청에 Mcp-Session-Id header를 보내지 않는다.
# 기밀 client(visibility-shop-agent)로 밟는다. consent가 저장되어, step-up에서 새 scope만 묻는 화면을 볼 수 있다.
# 사용: auth-server(:9050)와 shop-mcp-server(:8161)를 새로 띄운 직후에 실행한다(저장된 consent가 없고 재고가 처음 값이어야 한다).
#   ./mcp-visibility-walkthrough.sh > 결과.txt
# 출력의 token은 줄인다 — JWT는 앞 20자, refresh_token과 인가 코드는 앞 12자 뒤에 "...".
set -uo pipefail

AS=${AS:-http://localhost:9050}
MCP_BASE=${MCP_BASE:-http://localhost:8161}
MCP="$MCP_BASE/mcp"
CLIENT_ID=${CLIENT_ID:-visibility-shop-agent}
CLIENT_SECRET=${CLIENT_SECRET:-visibility-shop-agent-secret}
REDIRECT_URI=${REDIRECT_URI:-http://localhost:8160/login/oauth2/code/authserver}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
PROTOCOL_VERSION=2025-11-25

# RFC 7636 부록 B의 예시 값이다.
VERIFIER=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
CHALLENGE=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM

# user와 user2는 cookie jar를 따로 둔다. JAR는 지금 login한 사용자를 가리키는 포인터다.
JAR=$(mktemp)
JAR2=$(mktemp)
trap 'rm -f "$JAR" "$JAR2"' EXIT

step() { printf '\n\n===== %s =====\n' "$1"; }
fail() { printf '\n[오류] %s\n' "$1" >&2; exit 1; }

tidy() {
  tr -d '\r' \
    | grep -vE '^(X-Content-Type-Options|X-XSS-Protection|X-Frame-Options|Expires|Date|Keep-Alive|Connection|Cache-Control|Pragma|Vary):' \
    | sed -E \
        -e 's/(eyJ[A-Za-z0-9_-]{17})[A-Za-z0-9_.-]+/\1.../g' \
        -e 's/("refresh_token":"[^"]{12})[^"]*"/\1..."/g' \
        -e 's/([?&]code=[^&[:space:]]{12})[^&[:space:]]*/\1.../g'
}

payload() {
  local segment="$1" pad
  pad=$(( (4 - ${#segment} % 4) % 4 ))
  [ "$pad" -gt 0 ] && segment="$segment$(printf '=%.0s' $(seq 1 $pad))"
  printf '%s' "$segment" | tr '_-' '/+' | base64 -d 2>/dev/null
  echo
}

location_of() { printf '%s' "$1" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p'; }

# login 화면을 열어 CSRF token을 읽고, username으로 로그인한다. 지금 JAR에 session cookie가 남는다.
login() {
  local username="$1" form csrf
  form=$(curl -s -c "$JAR" -b "$JAR" "$AS/login")
  csrf=$(printf '%s' "$form" | grep -o '<input[^>]*name="_csrf"[^>]*>' | head -1 | grep -o 'value="[^"]*"' | sed 's/^value="//;s/"$//')
  [ -n "$csrf" ] || fail "CSRF token을 찾지 못했다"
  curl -s -o /dev/null -w 'POST /login → HTTP %{http_code}\n' -c "$JAR" -b "$JAR" -X POST "$AS/login" \
    --data-urlencode "username=$username" --data-urlencode "password=$LOGIN_PASSWORD" --data-urlencode "_csrf=$csrf"
}

# authorization request를 보낸다. consent 화면이면 체크박스 줄을 보여 주고 PAGE_STATE를, 아니면 CODE를 채운다.
authorize() {
  local scope="$1" headers body
  body=$(mktemp)
  headers=$(curl -s -D - -o "$body" -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
    --data-urlencode response_type=code --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode "scope=$scope" \
    --data-urlencode state=state-1 --data-urlencode "code_challenge=$CHALLENGE" \
    --data-urlencode code_challenge_method=S256 --data-urlencode "resource=$MCP")
  PAGE_STATE=""
  CODE=""
  if printf '%s' "$headers" | head -1 | grep -q ' 200'; then
    echo "HTTP 200 — consent 화면. scope 선택 항목:"
    grep -o '<input class="form-check-input"[^>]*>' "$body"
    PAGE_STATE=$(grep -o 'name="state" value="[^"]*"' "$body" | head -1 | sed 's/.*value="//;s/"$//')
  else
    printf '%s\n' "$headers" | grep -iE '^(HTTP|location):' | tidy
    CODE=$(location_of "$headers" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
  fi
  rm -f "$body"
}

# consent 화면에서 주어진 scope를 체크해 제출하고 CODE를 채운다.
consent() {
  local args=(--data-urlencode "client_id=$CLIENT_ID" --data-urlencode "state=$PAGE_STATE") headers
  for s in "$@"; do args+=(--data-urlencode "scope=$s"); done
  echo "체크한 scope: ${*:-(없음)}"
  headers=$(curl -s -D - -o /dev/null -c "$JAR" -b "$JAR" -X POST "$AS/oauth2/authorize" "${args[@]}")
  printf '%s\n' "$headers" | grep -iE '^(HTTP|location):' | tidy
  CODE=$(location_of "$headers" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
}

# CODE를 token으로 바꾸고 TOKEN을 채운다.
token() {
  [ -n "$CODE" ] || fail "authorization code가 없다"
  local body
  body=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" "$AS/oauth2/token" -d grant_type=authorization_code \
    --data-urlencode "code=$CODE" --data-urlencode "redirect_uri=$REDIRECT_URI" \
    -d "code_verifier=$VERIFIER" --data-urlencode "resource=$MCP")
  printf '%s\n' "$body" | tidy
  TOKEN=$(printf '%s' "$body" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
  [ -n "$TOKEN" ] || fail "access_token이 없다"
  echo "access token payload:"
  payload "$(printf '%s' "$TOKEN" | cut -d. -f2)"
}

# $1 token, $2 JSON 본문, 나머지는 curl 인자. Mcp-Session-Id는 어디서도 보내지 않는다(서버가 stateless다).
mcp() {
  local token="$1" body="$2"; shift 2
  curl -s -i -X POST "$MCP" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "MCP-Protocol-Version: $PROTOCOL_VERSION" -H "Authorization: Bearer $token" "$@" -d "$body" | tidy
}

# $1 token, $2 tool 이름, $3 arguments JSON.
tool() {
  mcp "$1" "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}"
}


# tools/list 응답(header와 본문)을 받아 tool 이름을 순서대로 쉼표로 잇는다.
# 본문은 마지막 줄이다. python3로 JSON을 읽는다.
names() {
  python3 -c '
import json, sys
body = sys.stdin.read().strip().splitlines()[-1]
print(",".join(tool["name"] for tool in json.loads(body)["result"]["tools"]))
'
}

# 응답 첫 줄(HTTP/1.1 200)에서 상태 코드를 뽑는다.
status_of() { printf '%s\n' "$1" | head -1 | awk '{print $2}'; }

# $1 token. 이 token으로 보이는 tool 목록을 요청한다.
list_tools() {
  mcp "$1" '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
}

# 쉼표로 이은 이름 목록에서 개수를 센다.
count_of() { printf '%s\n' "$1" | tr ',' '\n' | grep -c .; }

step "V1. user(점원) login → authorize(openid products:read) → consent → token(STAFF_READ) → tools/list(7개)"
login user
authorize "openid products:read"
consent products:read
step "V1-token. token request"
token
STAFF_READ=$TOKEN
step "V1-list. STAFF_READ로 tools/list"
RESPONSE=$(list_tools "$STAFF_READ")
printf '%s\n' "$RESPONSE"
STAFF_NAMES=$(printf '%s\n' "$RESPONSE" | names)
echo "names($(count_of "$STAFF_NAMES")개): $STAFF_NAMES"
[ "$(count_of "$STAFF_NAMES")" = 7 ] || fail "점원의 tool이 7개가 아니다"

step "V2. user2(손님, 새 JAR) login → 같은 흐름 → token(CUSTOMER_READ) → tools/list(updateStock만 빠진 6개)"
JAR="$JAR2"
login user2
authorize "openid products:read"
consent products:read
step "V2-token. token request"
token
CUSTOMER_READ=$TOKEN
step "V2-list. CUSTOMER_READ로 tools/list"
RESPONSE=$(list_tools "$CUSTOMER_READ")
printf '%s\n' "$RESPONSE"
CUSTOMER_NAMES=$(printf '%s\n' "$RESPONSE" | names)
echo "names($(count_of "$CUSTOMER_NAMES")개): $CUSTOMER_NAMES"
EXPECTED=$(printf '%s\n' "$STAFF_NAMES" | tr ',' '\n' | grep -vx updateStock | paste -sd, -)
echo "점원 목록에서 updateStock만 뺀 것: $EXPECTED"
[ "$CUSTOMER_NAMES" = "$EXPECTED" ] || fail "손님의 목록이 점원 목록에서 updateStock만 뺀 것과 다르다"

step "V3. CUSTOMER_READ로 목록에 없는 updateStock 호출 → 응답 header 전체와 본문(200, WWW-Authenticate 없음, Unknown tool)"
HIDDEN=$(tool "$CUSTOMER_READ" updateStock '{"productId":"p1","quantity":10}')
printf '%s\n' "$HIDDEN"
[ "$(status_of "$HIDDEN")" = 200 ] || fail "숨긴 tool 호출이 200이 아니다"
printf '%s\n' "$HIDDEN" | grep -qi '^WWW-Authenticate:' && fail "숨긴 tool 호출 응답에 WWW-Authenticate가 있다"
printf '%s\n' "$HIDDEN" | grep -q 'Unknown tool' || fail "숨긴 tool 호출이 Unknown tool이 아니다"

step "V4. CUSTOMER_READ로 같은 길이의 없는 이름 updateStack 호출 → 응답 header 전체와 본문"
MISSING=$(tool "$CUSTOMER_READ" updateStack '{"productId":"p1","quantity":10}')
printf '%s\n' "$MISSING"
echo
echo "이름을 NAME으로 바꿔 V3과 V4를 견준다."
[ "${HIDDEN//updateStock/NAME}" = "${MISSING//updateStack/NAME}" ] || fail "숨긴 tool과 없는 tool의 응답이 이름 말고도 다르다"
echo "→ 이름만 다르고 응답은 같다."

step "V5. STAFF_READ로 updateStock 호출 → 403, WWW-Authenticate(scope=\"products:write\")"
STAFF_CALL=$(tool "$STAFF_READ" updateStock '{"productId":"p1","quantity":10}')
printf '%s\n' "$STAFF_CALL"
[ "$(status_of "$STAFF_CALL")" = 403 ] || fail "점원의 updateStock 호출이 403이 아니다"
printf '%s\n' "$STAFF_CALL" | grep -i '^WWW-Authenticate:' | grep -q 'scope="products:write"' || fail "WWW-Authenticate에 scope=\"products:write\"가 없다"

step "V6. STAFF_READ로 tools/list를 한 번 더 → 목록이 V1과 같다"
RESPONSE=$(list_tools "$STAFF_READ")
AGAIN_NAMES=$(printf '%s\n' "$RESPONSE" | names)
echo "names($(count_of "$AGAIN_NAMES")개): $AGAIN_NAMES"
[ "$AGAIN_NAMES" = "$STAFF_NAMES" ] || fail "두 번째 목록이 V1과 다르다"

step "V7. user2가 products:write까지 요청 → authorize(openid products:read products:write) → consent → token(CUSTOMER_WRITE)"
authorize "openid products:read products:write"
consent products:write
step "V7-token. products:write가 든 token"
token
CUSTOMER_WRITE=$TOKEN
step "V7-list. CUSTOMER_WRITE로 tools/list → V2와 같다"
RESPONSE=$(list_tools "$CUSTOMER_WRITE")
WRITE_NAMES=$(printf '%s\n' "$RESPONSE" | names)
echo "names($(count_of "$WRITE_NAMES")개): $WRITE_NAMES"
[ "$WRITE_NAMES" = "$CUSTOMER_NAMES" ] || fail "products:write token으로 받은 목록이 V2와 다르다"
step "V7-before. getStock(p1)"
BEFORE=$(tool "$CUSTOMER_WRITE" getStock '{"productId":"p1"}')
printf '%s\n' "$BEFORE"
step "V7-call. CUSTOMER_WRITE로 updateStock(p1, 10) → 여전히 Unknown tool"
WRITE_CALL=$(tool "$CUSTOMER_WRITE" updateStock '{"productId":"p1","quantity":10}')
printf '%s\n' "$WRITE_CALL"
printf '%s\n' "$WRITE_CALL" | grep -q 'Unknown tool' || fail "products:write token으로도 Unknown tool이 아니다"
step "V7-after. getStock(p1) → 재고가 그대로다"
AFTER=$(tool "$CUSTOMER_WRITE" getStock '{"productId":"p1"}')
printf '%s\n' "$AFTER"
[ "$BEFORE" = "$AFTER" ] || fail "재고가 바뀌었다"
echo "→ 재고가 그대로다."
