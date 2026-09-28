#!/usr/bin/env bash
# mcp-security-authz practice 의 scope 와 step-up 흐름을 curl 로 한 단계씩 밟으며 기록한다(A 번호).
# 기밀 client(authz-shop-agent)로 밟는다. consent 가 저장되어, step-up 에서 새 scope 만 묻는 화면을 볼 수 있다.
# 사용: auth-server(:9030)와 shop-mcp-server(:8141)를 새로 띄운 직후에 실행한다(저장된 consent 가 없어야 한다).
#   ./mcp-authz-walkthrough.sh > 결과.txt
# 출력의 token 은 줄인다 — JWT 는 앞 20자, refresh_token 과 인가 코드는 앞 12자 뒤에 "...".
set -uo pipefail

AS=${AS:-http://localhost:9030}
MCP_BASE=${MCP_BASE:-http://localhost:8141}
MCP="$MCP_BASE/mcp"
CLIENT_ID=${CLIENT_ID:-authz-shop-agent}
CLIENT_SECRET=${CLIENT_SECRET:-authz-shop-agent-secret}
REDIRECT_URI=${REDIRECT_URI:-http://localhost:8140/login/oauth2/code/authserver}
LOGIN_USERNAME=${LOGIN_USERNAME:-user}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
PROTOCOL_VERSION=2025-11-25

# RFC 7636 부록 B 의 예시 값이다.
VERIFIER=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
CHALLENGE=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM

JAR=$(mktemp)
trap 'rm -f "$JAR"' EXIT

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

# authorization request 를 보낸다. consent 화면이면 체크박스 줄을 보여 주고 PAGE_STATE 를, 아니면 CODE 를 채운다.
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

# consent 화면에서 주어진 scope 를 체크해 제출하고 CODE 를 채운다.
consent() {
  local args=(--data-urlencode "client_id=$CLIENT_ID" --data-urlencode "state=$PAGE_STATE") headers
  for s in "$@"; do args+=(--data-urlencode "scope=$s"); done
  echo "체크한 scope: ${*:-(없음)}"
  headers=$(curl -s -D - -o /dev/null -c "$JAR" -b "$JAR" -X POST "$AS/oauth2/authorize" "${args[@]}")
  printf '%s\n' "$headers" | grep -iE '^(HTTP|location):' | tidy
  CODE=$(location_of "$headers" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
}

# CODE 를 token 으로 바꾸고 TOKEN 을 채운다.
token() {
  [ -n "$CODE" ] || fail "authorization code 가 없다"
  local body
  body=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" "$AS/oauth2/token" -d grant_type=authorization_code \
    --data-urlencode "code=$CODE" --data-urlencode "redirect_uri=$REDIRECT_URI" \
    -d "code_verifier=$VERIFIER" --data-urlencode "resource=$MCP")
  printf '%s\n' "$body" | tidy
  TOKEN=$(printf '%s' "$body" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
  [ -n "$TOKEN" ] || fail "access_token 이 없다"
  echo "access token payload:"
  payload "$(printf '%s' "$TOKEN" | cut -d. -f2)"
}

mcp() {  # $1 token, $2 JSON 본문, 나머지는 curl 인자
  local token="$1" body="$2"; shift 2
  curl -s -i -X POST "$MCP" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "MCP-Protocol-Version: $PROTOCOL_VERSION" -H "Authorization: Bearer $token" "$@" -d "$body" | tidy
}

UPDATE_STOCK='{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"updateStock","arguments":{"productId":"p1","quantity":10}}}'

step "A1. token 없이 부르면 401 과 처음 요청할 scope"
curl -s -i -X POST "$MCP" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}' \
  | tidy | head -8

step "A2. PRM 의 scopes_supported"
curl -s "$MCP_BASE/.well-known/oauth-protected-resource/mcp"; echo

step "A3. login"
FORM=$(curl -s -c "$JAR" -b "$JAR" "$AS/login")
CSRF=$(printf '%s' "$FORM" | grep -o '<input[^>]*name="_csrf"[^>]*>' | head -1 | grep -o 'value="[^"]*"' | sed 's/^value="//;s/"$//')
[ -n "$CSRF" ] || fail "CSRF token 을 찾지 못했다"
curl -s -o /dev/null -w 'POST /login → HTTP %{http_code}\n' -c "$JAR" -b "$JAR" -X POST "$AS/login" \
  --data-urlencode "username=$LOGIN_USERNAME" --data-urlencode "password=$LOGIN_PASSWORD" --data-urlencode "_csrf=$CSRF"

step "A4. 최소 scope(openid products:read)로 authorization — consent 화면"
authorize "openid products:read"
consent products:read
step "A4-token. token request"
token
READ_TOKEN=$TOKEN

step "A5. 조회 token 으로 initialize 와 tools/list"
INIT=$(mcp "$READ_TOKEN" '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}')
printf '%s\n' "$INIT" | head -12
SESSION=$(printf '%s' "$INIT" | sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d: //p' | head -1)
[ -n "$SESSION" ] || fail "Mcp-Session-Id 가 없다"
mcp "$READ_TOKEN" '{"jsonrpc":"2.0","method":"notifications/initialized"}' -H "Mcp-Session-Id: $SESSION" | head -1
mcp "$READ_TOKEN" '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' -H "Mcp-Session-Id: $SESSION" \
  | grep -o '"name":"[A-Za-z]*"' | sort -u

step "A6. 조회 token 으로 재고 변경 — 403 insufficient_scope"
mcp "$READ_TOKEN" "$UPDATE_STOCK" -H "Mcp-Session-Id: $SESSION" | head -6

step "A7. step-up(openid products:read products:write) — 새 scope 만 묻는 consent 화면, products:write 를 체크하지 않는다"
authorize "openid products:read products:write"
consent
step "A7-token. 이전 scope 만 담긴 token"
token
step "A7-call. 다시 재고 변경 — 여전히 403"
mcp "$TOKEN" "$UPDATE_STOCK" -H "Mcp-Session-Id: $SESSION" | head -6

step "A8. step-up 을 다시 하고 products:write 를 체크한다"
authorize "openid products:read products:write"
consent products:write
step "A8-token. 두 scope 가 담긴 token"
token
step "A8-call. 재고 변경 — 200"
mcp "$TOKEN" "$UPDATE_STOCK" -H "Mcp-Session-Id: $SESSION"
