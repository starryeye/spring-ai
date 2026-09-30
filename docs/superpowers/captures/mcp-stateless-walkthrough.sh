#!/usr/bin/env bash
# mcp-stateless-handle practice의 stateless 서버와 장바구니 handle 흐름을 curl로 한 단계씩 기록한다(S 번호).
# 서버는 session을 만들지 않는다. 그래서 모든 MCP 요청에 Mcp-Session-Id header를 보내지 않는다.
# 기밀 client(stateless-shop-agent)로 밟는다. consent가 저장되어, step-up에서 새 scope만 묻는 화면을 볼 수 있다.
# 사용: auth-server(:9040)와 shop-mcp-server(:8151)를 새로 띄운 직후에 실행한다(저장된 consent가 없어야 한다).
#   ./mcp-stateless-walkthrough.sh > 결과.txt
# 출력의 token은 줄인다 — JWT는 앞 20자, refresh_token과 인가 코드는 앞 12자 뒤에 "...".
set -uo pipefail

AS=${AS:-http://localhost:9040}
MCP_BASE=${MCP_BASE:-http://localhost:8151}
MCP="$MCP_BASE/mcp"
CLIENT_ID=${CLIENT_ID:-stateless-shop-agent}
CLIENT_SECRET=${CLIENT_SECRET:-stateless-shop-agent-secret}
REDIRECT_URI=${REDIRECT_URI:-http://localhost:8150/login/oauth2/code/authserver}
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

step "S1. token 없이 initialize → 401"
curl -s -i -X POST "$MCP" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}' \
  | tidy

step "S2. user login → authorize(openid products:read) → consent → token(READ_TOKEN)"
login user
authorize "openid products:read"
consent products:read
step "S2-token. token request"
token
READ_TOKEN=$TOKEN

step "S3. READ_TOKEN으로 initialize → 응답 header 전체(Mcp-Session-Id가 없어야 한다)"
INIT_RESPONSE=$(mcp "$READ_TOKEN" '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}')
printf '%s\n' "$INIT_RESPONSE"
printf '%s' "$INIT_RESPONSE" | grep -qi '^Mcp-Session-Id:' && fail "Mcp-Session-Id가 있다"

step "S4. GET /mcp(Accept: text/event-stream) → 405, DELETE /mcp → 404"
curl -s -i -X GET "$MCP" -H 'Accept: text/event-stream' -H "MCP-Protocol-Version: $PROTOCOL_VERSION" \
  -H "Authorization: Bearer $READ_TOKEN" | tidy | head -1
curl -s -i -X DELETE "$MCP" -H "MCP-Protocol-Version: $PROTOCOL_VERSION" \
  -H "Authorization: Bearer $READ_TOKEN" | tidy | head -1

step "S5. createBasket → handle"
BODY=$(tool "$READ_TOKEN" createBasket '{}')
printf '%s\n' "$BODY"
HANDLE=$(printf '%s' "$BODY" | sed -n 's/.*"basketId":"\(bsk_[^"]*\)".*/\1/p' | head -1)
[ -n "$HANDLE" ] || fail "createBasket이 handle을 돌려주지 않았다"
echo "HANDLE=$HANDLE"

step "S6. addItem(HANDLE, p4, 1) → getBasket(HANDLE)"
tool "$READ_TOKEN" addItem "{\"basketId\":\"$HANDLE\",\"productId\":\"p4\",\"quantity\":1}"
tool "$READ_TOKEN" getBasket "{\"basketId\":\"$HANDLE\"}"

step "S7. user2(새 JAR)로 login → authorize → consent → token(USER2_TOKEN) → getBasket(HANDLE)은 찾을 수 없다"
USER_JAR="$JAR"
JAR="$JAR2"
login user2
authorize "openid products:read"
consent products:read
step "S7-token. token request"
token
USER2_TOKEN=$TOKEN
step "S7-call. user2로 getBasket(HANDLE)"
tool "$USER2_TOKEN" getBasket "{\"basketId\":\"$HANDLE\"}"
JAR="$USER_JAR"

step "S8. READ_TOKEN으로 checkout(HANDLE) → 403 insufficient_scope(orders:write)"
tool "$READ_TOKEN" checkout "{\"basketId\":\"$HANDLE\"}"

step "S9. user의 JAR로 돌아와 step-up(openid products:read orders:write) → checkout(HANDLE)"
authorize "openid products:read orders:write"
consent orders:write
step "S9-token. 두 scope가 담긴 token"
token
step "S9-call. checkout(HANDLE) → 주문 접수"
tool "$TOKEN" checkout "{\"basketId\":\"$HANDLE\"}"

step "S10. 같은 HANDLE로 다시 checkout → 이미 주문했다"
tool "$TOKEN" checkout "{\"basketId\":\"$HANDLE\"}"
