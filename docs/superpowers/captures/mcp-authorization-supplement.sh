#!/usr/bin/env bash
# MCP 안내서(practice/mcp-guide/)의 부록 reference-api.md·reference-compliance.md 가 인용하는 보충 관측(S 번호)을 curl 로 기록한다.
# mcp-authorization-walkthrough.sh(C 번호)가 다루지 않는 응답을 모은다 —
# OIDC 디스커버리, 루트형 보호 리소스 메타데이터, 인가·토큰 엔드포인트 오류, 전송 계층 오류(Host·Accept·세션),
# GET /mcp, 에이전트가 만드는 인가 요청과 콜백의 iss 검증.
#
# 사용: practice 를 run.sh 로 띄운 뒤(기본값은 official)
#   AS=... MCP_BASE=... AGENT=... CLIENT_ID=... CLIENT_SECRET=... LOGIN_USERNAME=... LOGIN_PASSWORD=... \
#     ./mcp-authorization-supplement.sh > 결과.txt
# 출력에 남는 토큰은 이 스크립트가 줄인다 — JWT 는 앞 20자, refresh_token 과 인가 코드는 앞 12자 뒤에 "...".
# 응답의 공통 보안 헤더(X-Content-Type-Options 등)와 Date 는 뺀다.
set -uo pipefail

# 값 추출 직후 호출한다. 비어 있으면 어느 단계에서 무엇이 비었는지 알리고 즉시 멈춘다
# (빈 값으로 뒷단계까지 조용히 흘려보내 진단 없이 뒤엉킨 캡처를 만들지 않기 위함).
require() {
  local label="$1" value="$2" step="$3"
  if [ -z "$value" ]; then
    printf '\n[오류] "%s" 단계에서 %s 값을 추출하지 못했습니다(빈 문자열). 위 응답 본문/헤더를 확인하세요.\n' "$step" "$label" >&2
    exit 1
  fi
}

AS=${AS:-http://localhost:9010}
MCP_BASE=${MCP_BASE:-http://localhost:8111}
MCP="$MCP_BASE/mcp"
AGENT=${AGENT:-http://localhost:8110}
CLIENT_ID=${CLIENT_ID:-official-shop-agent}
CLIENT_SECRET=${CLIENT_SECRET:-official-shop-agent-secret}
REDIRECT_URI=${REDIRECT_URI:-$AGENT/login/oauth2/code/authserver}
LOGIN_USERNAME=${LOGIN_USERNAME:-user}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
PROTOCOL_VERSION=${PROTOCOL_VERSION:-2025-11-25}

# RFC 7636 부록 B 의 예시 값이다.
VERIFIER=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
CHALLENGE=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM

JAR=$(mktemp)        # 인가 서버에 로그인한 브라우저의 쿠키
AGENT_JAR=$(mktemp)  # 에이전트에 접속한 브라우저의 쿠키
HEADERS=$(mktemp)
BODY=$(mktemp)
trap 'rm -f "$JAR" "$AGENT_JAR" "$HEADERS" "$BODY"' EXIT

INITIALIZE='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"supplement","version":"1.0.0"}}}'

step() { printf '\n\n===== %s =====\n' "$1"; }

# 문서에 인용할 형태로 다듬는다. 공통 보안 헤더와 Date 를 빼고, 토큰·코드를 줄인다.
tidy() {
  tr -d '\r' \
    | grep -vE '^(X-Content-Type-Options|X-XSS-Protection|X-Frame-Options|Expires|Date|Keep-Alive|Connection):' \
    | sed -E \
        -e 's/(eyJ[A-Za-z0-9_-]{17})[A-Za-z0-9_.-]+/\1.../g' \
        -e 's/("refresh_token":"[^"]{12})[^"]*"/\1..."/g' \
        -e 's/([?&]code=[^&[:space:]]{12})[^&[:space:]]*/\1.../g'
}

location_of() { printf '%s' "$1" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p'; }
query_param() { printf '%s' "$1" | sed -n "s/.*[?&]$2=\([^&]*\).*/\1/p"; }

# 인가 서버 폼으로 로그인한다. 이후 인가 요청은 이 쿠키(JAR)로 로그인 화면 없이 코드를 받는다.
login() {
  local form tag csrf response location
  form=$(curl -s -c "$JAR" -b "$JAR" "$AS/login")
  tag=$(printf '%s' "$form" | grep -o '<input[^>]*name="_csrf"[^>]*>' | head -1)
  csrf=$(printf '%s' "$tag" | grep -o 'value="[^"]*"' | head -1 | sed 's/^value="//;s/"$//')
  require "CSRF" "$csrf" "로그인"
  response=$(curl -si -c "$JAR" -b "$JAR" -X POST "$AS/login" \
    --data-urlencode "username=$LOGIN_USERNAME" --data-urlencode "password=$LOGIN_PASSWORD" \
    --data-urlencode "_csrf=$csrf")
  location=$(location_of "$response")
  if [ -z "$location" ] || printf '%s' "$location" | grep -q '/login?error'; then
    printf '\n[오류] 로그인이 실패했습니다: Location=%s\n' "$location" >&2
    exit 1
  fi
}

# PKCE S256 과 resource 를 실은 인가 요청을 보내고 응답 전체(헤더 포함)를 돌려준다.
# $1 = resource, $2 = redirect_uri
authorize() {
  curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
    --data-urlencode 'response_type=code' --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "redirect_uri=$2" --data-urlencode 'scope=openid profile' \
    --data-urlencode 'state=supplement-state' --data-urlencode "code_challenge=$CHALLENGE" \
    --data-urlencode 'code_challenge_method=S256' --data-urlencode "resource=$1"
}

# 새 인가 코드를 하나 받는다. 코드는 한 번만 쓸 수 있으므로 토큰 요청마다 새로 받는다.
new_code() {
  local step_name="$1" response code
  response=$(authorize "$MCP" "$REDIRECT_URI")
  code=$(query_param "$(location_of "$response")" code)
  require "CODE" "$code" "$step_name"
  CODE="$code"
}

MCP_HEADERS=(-H "Authorization: Bearer PLACEHOLDER")

printf '# 보충 관측 — %s (%s)\n' "$(date +%F)" "$(basename "$0")"
printf '# AS=%s MCP=%s AGENT=%s CLIENT_ID=%s\n' "$AS" "$MCP" "$AGENT" "$CLIENT_ID"

step "S1. OIDC 디스커버리 (GET /.well-known/openid-configuration)"
curl -si "$AS/.well-known/openid-configuration" | tidy

step "S2. 루트형 보호 리소스 메타데이터 (GET /.well-known/oauth-protected-resource)"
curl -si "$MCP_BASE/.well-known/oauth-protected-resource" | tidy

step "S3. 형식이 잘못된 토큰으로 MCP 호출 (401 invalid_token)"
curl -si -X POST "$MCP" -H 'Authorization: Bearer not-a-jwt' -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -d "$INITIALIZE" | tidy

login

step "S4. 등록되지 않은 redirect_uri 로 인가 요청 (리다이렉트하지 않는다)"
authorize "$MCP" "http://evil.example/callback" | tidy | cut -c1-300 | head -12

step "S5. 토큰 요청 성공 — 응답 헤더와 필드 (authorization_code)"
new_code "S5"
TOKEN_RESPONSE=$(curl -si -u "$CLIENT_ID:$CLIENT_SECRET" -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=authorization_code' --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode "code_verifier=$VERIFIER" \
  --data-urlencode "resource=$MCP")
printf '%s\n' "$TOKEN_RESPONSE" | tidy
ACCESS=$(printf '%s' "$TOKEN_RESPONSE" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
require "ACCESS_TOKEN" "$ACCESS" "S5. 토큰 요청 성공"
MCP_HEADERS=(-H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json'
  -H 'Accept: application/json, text/event-stream')

step "S6. 토큰 요청의 resource 가 인가 요청과 다름 (invalid_target)"
new_code "S6"
curl -si -u "$CLIENT_ID:$CLIENT_SECRET" -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=authorization_code' --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode "code_verifier=$VERIFIER" \
  --data-urlencode 'resource=http://localhost:9999/mcp' | tidy

step "S7. code_verifier 불일치 (invalid_grant)"
new_code "S7"
curl -si -u "$CLIENT_ID:$CLIENT_SECRET" -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=authorization_code' --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  --data-urlencode 'code_verifier=wrong-verifier-wrong-verifier-wrong-verifier-000' \
  --data-urlencode "resource=$MCP" | tidy

step "S8. Basic 인증 실패 (invalid_client)"
curl -si -u "$CLIENT_ID:wrong-secret" -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=authorization_code' --data-urlencode 'code=unused' \
  --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode "code_verifier=$VERIFIER" \
  --data-urlencode "resource=$MCP" | tidy

step "S9. initialize 와 notifications/initialized"
INIT_RESPONSE=$(curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -d "$INITIALIZE")
printf '%s\n' "$INIT_RESPONSE" | tidy
SESSION=$(printf '%s' "$INIT_RESPONSE" | tr -d '\r' | sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d: //p')
require "SESSION" "$SESSION" "S9. initialize"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -H "Mcp-Session-Id: $SESSION" \
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  | tidy | head -1

step "S10. MCP-Protocol-Version 헤더 없이 tools/list"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | tidy | cut -c1-160 | head -8

step "S11. GET /mcp — 세션의 SSE 스트림 열기 (5초 동안 관측)"
curl -s -N -D "$HEADERS" -o "$BODY" --max-time 5 "$MCP" -H "Authorization: Bearer $ACCESS" \
  -H 'Accept: text/event-stream' -H "Mcp-Session-Id: $SESSION" -H "MCP-Protocol-Version: $PROTOCOL_VERSION"
GET_EXIT=$?
printf 'curl 종료 코드: %s (0 = 서버가 응답을 끝냄, 28 = 5초가 지나도록 연결이 열려 있어 curl 이 끊음)\n' "$GET_EXIT"
printf '받은 응답 헤더: %s 바이트\n' "$(wc -c < "$HEADERS" | tr -d ' ')"
tidy < "$HEADERS"
printf '받은 본문: %s 바이트\n' "$(wc -c < "$BODY" | tr -d ' ')"
tidy < "$BODY" | cut -c1-160 | head -5

step "S12. GET /mcp — Mcp-Session-Id 없이"
curl -si --max-time 5 "$MCP" -H "Authorization: Bearer $ACCESS" -H 'Accept: text/event-stream' \
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION" | tidy | cut -c1-300

step "S13. POST /mcp — 존재하지 않는 Mcp-Session-Id"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -H 'Mcp-Session-Id: 00000000-0000-0000-0000-000000000000' \
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION" -d '{"jsonrpc":"2.0","id":3,"method":"tools/list"}' \
  | tidy | cut -c1-300

step "S14. 허용되지 않은 Host (DNS 리바인딩 방어)"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -H "Host: evil.example:${MCP_BASE##*:}" \
  -H "Mcp-Session-Id: $SESSION" -H "MCP-Protocol-Version: $PROTOCOL_VERSION" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/list"}' | tidy

step "S15. Accept 에 text/event-stream 이 없음"
curl -si -X POST "$MCP" -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -H 'Accept: application/json' -H "Mcp-Session-Id: $SESSION" -H "MCP-Protocol-Version: $PROTOCOL_VERSION" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/list"}' | tidy | cut -c1-300

step "S16. DELETE 로 세션을 끝낸 뒤 같은 세션으로 tools/list"
curl -si -X DELETE "$MCP" -H "Authorization: Bearer $ACCESS" -H "Mcp-Session-Id: $SESSION" \
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION" | tidy
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -H "Mcp-Session-Id: $SESSION" \
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION" -d '{"jsonrpc":"2.0","id":6,"method":"tools/list"}' \
  | tidy | cut -c1-300

step "S17. 에이전트가 만드는 인가 요청 (발견 결과로 만든다)"
AGENT_AUTH=$(curl -si -c "$AGENT_JAR" -b "$AGENT_JAR" "$AGENT/oauth2/authorization/authserver")
printf '%s\n' "$AGENT_AUTH" | tidy | grep -iE '^(HTTP|Location)'
AGENT_STATE=$(query_param "$(location_of "$AGENT_AUTH")" state)
require "STATE" "$AGENT_STATE" "S17. 에이전트가 만드는 인가 요청"

step "S18. 조작한 iss 로 에이전트 콜백 (코드를 교환하지 않고 거부)"
curl -si -c "$AGENT_JAR" -b "$AGENT_JAR" \
  "$REDIRECT_URI?code=forged-code&state=$AGENT_STATE&iss=http%3A%2F%2Fevil.example" | tidy

step "S19. iss 없이 에이전트 콜백 (인가 서버가 iss 지원을 광고했으므로 거부)"
AGENT_AUTH=$(curl -si -c "$AGENT_JAR" -b "$AGENT_JAR" "$AGENT/oauth2/authorization/authserver")
AGENT_STATE=$(query_param "$(location_of "$AGENT_AUTH")" state)
require "STATE" "$AGENT_STATE" "S19. iss 없이 에이전트 콜백"
curl -si -c "$AGENT_JAR" -b "$AGENT_JAR" "$REDIRECT_URI?code=forged-code&state=$AGENT_STATE" | tidy

step "S20. 에이전트 정상 로그인 왕복 (브라우저 역할)"
AGENT_AUTH=$(curl -si -c "$AGENT_JAR" -b "$AGENT_JAR" "$AGENT/oauth2/authorization/authserver")
AGENT_LOCATION=$(location_of "$AGENT_AUTH")
require "AGENT_LOCATION" "$AGENT_LOCATION" "S20. 에이전트 정상 로그인 왕복"
# 인가 서버에 로그인해 둔 브라우저(JAR)가 그 인가 요청 URL 을 연다.
AS_RESPONSE=$(curl -si -c "$JAR" -b "$JAR" "$AGENT_LOCATION")
CALLBACK=$(location_of "$AS_RESPONSE")
require "CALLBACK" "$CALLBACK" "S20. 에이전트 정상 로그인 왕복"
printf '인가 서버 → %s\n' "$CALLBACK" | tidy
curl -si -c "$AGENT_JAR" -b "$AGENT_JAR" "$CALLBACK" | tidy | grep -iE '^(HTTP|Location)'

step "S21. 세 프로세스의 수신 주소 (lsof)"
lsof -nP -iTCP -sTCP:LISTEN \
  | awk -v ports=":${AS##*:}|:${MCP_BASE##*:}|:${AGENT##*:}" '$9 ~ "(" ports ")$" { print $1, $8, $9, $10 }'
