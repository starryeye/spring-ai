#!/usr/bin/env bash
# 인증이 포함된 MCP 흐름을 curl 로 한 단계씩 밟으며 요청과 응답을 기록한다.
# 사용: AS=... MCP_BASE=... CLIENT_ID=... LOGIN_USERNAME=... LOGIN_PASSWORD=... ./mcp-authorization-walkthrough.sh > 결과.txt
# 주의: 이 스크립트의 출력(결과.txt)에는 access/refresh/id 토큰 원문(JWT)이 그대로 남는다 — 공유 전 확인할 것.
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
CLIENT_ID=${CLIENT_ID:-official-shop-agent}
CLIENT_SECRET=${CLIENT_SECRET:-official-shop-agent-secret}
REDIRECT_URI=${REDIRECT_URI:-http://localhost:8110/login/oauth2/code/authserver}
LOGIN_USERNAME=${LOGIN_USERNAME:-user}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
PROTOCOL_VERSION=${PROTOCOL_VERSION:-2025-11-25}

# RFC 7636 부록 B 의 예시 값이다.
VERIFIER=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
CHALLENGE=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM

JAR=$(mktemp)
INITIALIZE='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"walkthrough","version":"1.0.0"}}}'

step() { printf '\n\n===== %s =====\n' "$1"; }
payload() {
  local segment="$1" pad
  pad=$(( (4 - ${#segment} % 4) % 4 ))
  [ "$pad" -gt 0 ] && segment="$segment$(printf '=%.0s' $(seq 1 $pad))"
  printf '%s' "$segment" | tr '_-' '/+' | base64 -d 2>/dev/null
  echo
}

step "1. 토큰 없이 MCP 를 호출한다 (401 + WWW-Authenticate)"
curl -si -X POST "$MCP" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -d "$INITIALIZE"

step "2. 보호 리소스 메타데이터 (RFC 9728, 경로형)"
curl -si "$MCP_BASE/.well-known/oauth-protected-resource/mcp"

step "3. 인가 서버 메타데이터 (RFC 8414)"
curl -si "$AS/.well-known/oauth-authorization-server"

step "4. 사용자 로그인 (인가 서버 폼)"
LOGIN_FORM=$(curl -s -c "$JAR" -b "$JAR" "$AS/login")
# name="_csrf" 와 value="..." 가 같은 <input> 태그 안에만 있으면 되고, 그 안에서
# 어느 속성이 먼저 오는지는 가리지 않는다(속성 순서에 의존하지 않는다).
CSRF_TAG=$(printf '%s' "$LOGIN_FORM" | grep -o '<input[^>]*name="_csrf"[^>]*>' | head -1)
CSRF=$(printf '%s' "$CSRF_TAG" | grep -o 'value="[^"]*"' | head -1 | sed 's/^value="//;s/"$//')
require "CSRF" "$CSRF" "4. 사용자 로그인 (인가 서버 폼)"
LOGIN_RESPONSE=$(curl -si -c "$JAR" -b "$JAR" -X POST "$AS/login" \
  --data-urlencode "username=$LOGIN_USERNAME" --data-urlencode "password=$LOGIN_PASSWORD" \
  --data-urlencode "_csrf=$CSRF")
echo "$LOGIN_RESPONSE" | head -8
LOGIN_LOCATION=$(printf '%s' "$LOGIN_RESPONSE" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p')
if printf '%s' "$LOGIN_LOCATION" | grep -q '/login?error'; then
  printf '\n[오류] 로그인이 실패했습니다: Location=%s\n' "$LOGIN_LOCATION" >&2
  exit 1
fi

step "5. 인가 요청 (PKCE S256 + resource)"
AUTHORIZE=$(curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
  --data-urlencode 'response_type=code' --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode 'scope=openid profile' \
  --data-urlencode 'state=walkthrough-state' --data-urlencode "code_challenge=$CHALLENGE" \
  --data-urlencode 'code_challenge_method=S256' --data-urlencode "resource=$MCP")
echo "$AUTHORIZE" | head -12
LOCATION=$(printf '%s' "$AUTHORIZE" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p')
CODE=$(printf '%s' "$LOCATION" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
require "CODE" "$CODE" "5. 인가 요청 (PKCE S256 + resource)"

step "6. 토큰 요청 (code_verifier + resource)"
TOKEN=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=authorization_code' --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode "code_verifier=$VERIFIER" \
  --data-urlencode "resource=$MCP")
echo "$TOKEN"
ACCESS=$(printf '%s' "$TOKEN" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
REFRESH=$(printf '%s' "$TOKEN" | sed -n 's/.*"refresh_token":"\([^"]*\)".*/\1/p')
ID_TOKEN=$(printf '%s' "$TOKEN" | sed -n 's/.*"id_token":"\([^"]*\)".*/\1/p')
require "ACCESS_TOKEN" "$ACCESS" "6. 토큰 요청 (code_verifier + resource)"
require "REFRESH_TOKEN" "$REFRESH" "6. 토큰 요청 (code_verifier + resource)"
require "ID_TOKEN" "$ID_TOKEN" "6. 토큰 요청 (code_verifier + resource)"

step "6-1. access token 페이로드 (aud 가 MCP 서버다)"
payload "$(printf '%s' "$ACCESS" | cut -d. -f2)"

step "6-2. id token 페이로드 (aud 는 클라이언트다)"
payload "$(printf '%s' "$ID_TOKEN" | cut -d. -f2)"

step "7. initialize (Bearer)"
INIT_RESPONSE=$(curl -si -X POST "$MCP" -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d "$INITIALIZE")
echo "$INIT_RESPONSE"
SESSION=$(printf '%s' "$INIT_RESPONSE" | tr -d '\r' | sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d: //p')
require "SESSION" "$SESSION" "7. initialize (Bearer)"

MCP_HEADERS=(-H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json'
  -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $SESSION"
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION")

step "8. notifications/initialized (202)"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

step "9. tools/list"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

step "10. tools/call"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getStock","arguments":{"productId":"p1"}}}'

step "11. refresh_token 으로 갱신 (resource 를 다시 싣는다)"
REFRESHED=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=refresh_token' --data-urlencode "refresh_token=$REFRESH" \
  --data-urlencode "resource=$MCP")
echo "$REFRESHED"
payload "$(printf '%s' "$REFRESHED" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p' | cut -d. -f2)"

step "12. 오류: aud 가 다른 토큰(id_token)을 Bearer 로 쓴다"
curl -si -X POST "$MCP" -H "Authorization: Bearer $ID_TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d "$INITIALIZE"

step "13. 오류: 허용되지 않은 Origin"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -H 'Origin: http://evil.example' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/list"}'

step "14. 오류: Mcp-Session-Id 없이 tools/list"
curl -si -X POST "$MCP" -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -H "MCP-Protocol-Version: $PROTOCOL_VERSION" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/list"}'

step "15. 오류: 지원하지 않는 MCP-Protocol-Version"
curl -si -X POST "$MCP" -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $SESSION" \
  -H 'MCP-Protocol-Version: 1999-01-01' -d '{"jsonrpc":"2.0","id":6,"method":"tools/list"}'

step "16. 오류: 모르는 resource 로 인가 요청 (invalid_target)"
curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
  --data-urlencode 'response_type=code' --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode 'scope=openid profile' \
  --data-urlencode 'state=walkthrough-state' --data-urlencode "code_challenge=$CHALLENGE" \
  --data-urlencode 'code_challenge_method=S256' --data-urlencode 'resource=http://localhost:9999/mcp' | head -8

step "17. 오류: PKCE 없는 인가 요청 (invalid_request)"
curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
  --data-urlencode 'response_type=code' --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode 'scope=openid profile' \
  --data-urlencode 'state=walkthrough-state' --data-urlencode "resource=$MCP" | head -8

step "18. 세션 종료 (DELETE)"
curl -si -X DELETE "$MCP" -H "Authorization: Bearer $ACCESS" -H "Mcp-Session-Id: $SESSION" \
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION"

rm -f "$JAR"
