#!/usr/bin/env bash
# 공개 클라이언트(RFC 6749 §2.1 public client — 비밀을 보관할 수 없는 클라이언트) 흐름을
# curl 로 한 단계씩 밟으며 기록한다(P 번호). 기밀 클라이언트 흐름은
# mcp-authorization-walkthrough.sh(C 번호)와 mcp-authorization-supplement.sh(S 번호)가 다룬다.
#
# 사전 조건: 동의 기록(OAuth2AuthorizationConsentService)은 (클라이언트, 사용자) 단위로 인가 서버
#   메모리에 남는다. P3~P5 가 동의 화면을 관측하려면 그 기록이 없어야 하므로, 이 스크립트는
#   갓 띄운 인가 서버에서 한 번 실행한다(재실행하려면 인가 서버를 다시 띄운다).
#
# 사용: practice 를 run.sh 로 띄운 뒤(기본값은 official)
#   AS=... MCP_BASE=... PUBLIC_CLIENT_ID=... CONFIDENTIAL_CLIENT_ID=... CONFIDENTIAL_CLIENT_SECRET=... \
#     CONFIDENTIAL_REDIRECT_URI=... LOGIN_USERNAME=... LOGIN_PASSWORD=... ./mcp-authorization-public-client.sh > 결과.txt
# 출력의 토큰은 줄인다 — JWT 는 앞 20자, refresh_token 과 인가 코드는 앞 12자 뒤에 "...".
set -uo pipefail

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
PUBLIC_CLIENT_ID=${PUBLIC_CLIENT_ID:-local-mcp-client}
PUBLIC_REDIRECT_URI=${PUBLIC_REDIRECT_URI:-http://127.0.0.1:8123/callback}
CONFIDENTIAL_CLIENT_ID=${CONFIDENTIAL_CLIENT_ID:-official-shop-agent}
CONFIDENTIAL_CLIENT_SECRET=${CONFIDENTIAL_CLIENT_SECRET:-official-shop-agent-secret}
CONFIDENTIAL_REDIRECT_URI=${CONFIDENTIAL_REDIRECT_URI:-http://localhost:8110/login/oauth2/code/authserver}
LOGIN_USERNAME=${LOGIN_USERNAME:-user}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
PROTOCOL_VERSION=${PROTOCOL_VERSION:-2025-11-25}

# RFC 7636 부록 B 의 예시 값이다.
VERIFIER=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
CHALLENGE=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM

JAR=$(mktemp)
trap 'rm -f "$JAR"' EXIT

INITIALIZE='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"public-client","version":"1.0.0"}}}'

step() { printf '\n\n===== %s =====\n' "$1"; }

tidy() {
  tr -d '\r' \
    | grep -vE '^(X-Content-Type-Options|X-XSS-Protection|X-Frame-Options|Expires|Date|Keep-Alive|Connection):' \
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
query_param() { printf '%s' "$1" | sed -n "s/.*[?&]$2=\([^&]*\).*/\1/p"; }

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

# 공개 클라이언트의 인가 요청. $1 = PKCE 를 실을지(yes/no), $2 = redirect_uri
public_authorize() {
  local pkce="$1" redirect="$2"
  if [ "$pkce" = yes ]; then
    curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
      --data-urlencode 'response_type=code' --data-urlencode "client_id=$PUBLIC_CLIENT_ID" \
      --data-urlencode "redirect_uri=$redirect" --data-urlencode 'scope=openid profile' \
      --data-urlencode 'state=public-state' --data-urlencode "code_challenge=$CHALLENGE" \
      --data-urlencode 'code_challenge_method=S256' --data-urlencode "resource=$MCP"
  else
    curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
      --data-urlencode 'response_type=code' --data-urlencode "client_id=$PUBLIC_CLIENT_ID" \
      --data-urlencode "redirect_uri=$redirect" --data-urlencode 'scope=openid profile' \
      --data-urlencode 'state=public-state' --data-urlencode "resource=$MCP"
  fi
}

# 동의 화면이 돌려준 hidden state 를 읽는다. 원래 인가 요청의 state 가 아니라,
# 대기 중인 인가를 찾으려고 인가 서버가 새로 발급한 값이다.
consent_state() { printf '%s' "$1" | grep -o 'name="state" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//'; }

# 동의 화면의 체크박스를 담아 같은 URI 로 POST 한다. openid 는 동의 대상이 아니라
# 체크박스가 없고, 서버가 자동으로 다시 붙인다.
consent_submit() {
  local state="$1"
  curl -si -c "$JAR" -b "$JAR" -X POST "$AS/oauth2/authorize" \
    --data-urlencode "client_id=$PUBLIC_CLIENT_ID" --data-urlencode "state=$state" \
    --data-urlencode 'scope=profile'
}

# 동의까지 마치고 새 인가 코드 하나를 받는다(코드는 한 번만 쓸 수 있다).
new_public_code() {
  local step_name="$1" response state location code
  response=$(public_authorize yes "$PUBLIC_REDIRECT_URI")
  if printf '%s' "$response" | head -1 | grep -q ' 200'; then
    state=$(consent_state "$response")
    require "CONSENT_STATE" "$state" "$step_name"
    response=$(consent_submit "$state")
  fi
  location=$(location_of "$response")
  code=$(query_param "$location" code)
  require "CODE" "$code" "$step_name"
  CODE="$code"
}

printf '# 공개 클라이언트 관측 — %s (%s)\n' "$(date +%F)" "$(basename "$0")"
printf '# AS=%s MCP=%s PUBLIC_CLIENT_ID=%s REDIRECT_URI=%s\n' "$AS" "$MCP" "$PUBLIC_CLIENT_ID" "$PUBLIC_REDIRECT_URI"

step "P1. 인가 서버 메타데이터의 token_endpoint_auth_methods_supported (RFC 8414 §2, none)"
curl -s "$AS/.well-known/oauth-authorization-server" \
  | sed -n 's/.*\("token_endpoint_auth_methods_supported":\[[^]]*\]\).*/\1/p'

step "P2. OIDC 디스커버리의 같은 필드"
curl -s "$AS/.well-known/openid-configuration" \
  | sed -n 's/.*\("token_endpoint_auth_methods_supported":\[[^]]*\]\).*/\1/p'

login

step "P3. 공개 클라이언트의 인가 요청 (PKCE S256 + resource) — 동의 화면"
AUTHORIZE=$(public_authorize yes "$PUBLIC_REDIRECT_URI")
if ! printf '%s' "$AUTHORIZE" | head -1 | grep -q ' 200'; then
  printf '\n[오류] 동의 화면 대신 %s 가 왔습니다. 이 (클라이언트, 사용자) 조합의 동의가 이미 기록돼 있습니다 — 인가 서버를 다시 띄운 뒤 실행하세요.\n' \
    "$(printf '%s' "$AUTHORIZE" | head -1 | tr -d '\r')" >&2
  exit 1
fi
printf '%s\n' "$AUTHORIZE" | tidy | grep -iE '^(HTTP|Content-Type|Content-Length)'
printf '%s\n' "$AUTHORIZE" | tidy | grep -oE '<p>[^<]*</p>|<input[^>]*name="(state|scope)"[^>]*>|<span class="scope-item">|scope[^<]{0,40}' | head -12

step "P4. 동의 화면의 scope 항목"
printf '%s\n' "$AUTHORIZE" | tidy | grep -oE 'id="[a-z]+"|value="[a-z]+"[^>]*id=|>[a-z]+</span>|profile|openid' | head -12

step "P5. 동의 제출 (POST /oauth2/authorize) → 인가 코드와 iss"
CONSENT_STATE=$(consent_state "$AUTHORIZE")
require "CONSENT_STATE" "$CONSENT_STATE" "P5. 동의 제출"
CONSENT_RESPONSE=$(consent_submit "$CONSENT_STATE")
printf '%s\n' "$CONSENT_RESPONSE" | tidy | grep -iE '^(HTTP|Location)'
CODE=$(query_param "$(location_of "$CONSENT_RESPONSE")" code)
require "CODE" "$CODE" "P5. 동의 제출"

step "P6. 토큰 요청 — 클라이언트 인증 없이 client_id 만 (OAuth 2.1 §3.2.2)"
TOKEN=$(curl -si -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=authorization_code' --data-urlencode "client_id=$PUBLIC_CLIENT_ID" \
  --data-urlencode "code=$CODE" --data-urlencode "redirect_uri=$PUBLIC_REDIRECT_URI" \
  --data-urlencode "code_verifier=$VERIFIER" --data-urlencode "resource=$MCP")
printf '%s\n' "$TOKEN" | tidy
ACCESS=$(printf '%s' "$TOKEN" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
require "ACCESS_TOKEN" "$ACCESS" "P6. 토큰 요청"

step "P6-1. access token 페이로드 — aud 는 클라이언트 유형과 무관하게 resource 다 (RFC 8707)"
payload "$(printf '%s' "$ACCESS" | cut -d. -f2)"

step "P7. 이 토큰으로 MCP initialize"
INIT=$(curl -si -X POST "$MCP" -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -d "$INITIALIZE")
printf '%s\n' "$INIT" | tidy | cut -c1-200
SESSION=$(printf '%s' "$INIT" | tr -d '\r' | sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d: //p')
require "SESSION" "$SESSION" "P7. MCP initialize"
curl -si -X POST "$MCP" -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $SESSION" \
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' | tidy | head -1
curl -si -X POST "$MCP" -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $SESSION" \
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getStock","arguments":{"productId":"p1"}}}' \
  | tidy | cut -c1-300

step "P8. 응답에 refresh_token 이 없다 — Spring 은 공개 클라이언트에 발급하지 않는다"
printf '응답 필드: '
printf '%s' "$TOKEN" | sed -n 's/.*{\(.*\)}.*/\1/p' | tr ',' '\n' | sed -n 's/^"\([a-z_]*\)".*/\1/p' | tr '\n' ' '
echo
printf '%s\n' "OAuth2RefreshTokenGenerator.generate() 가 authorization_code 그랜트에서 클라이언트 인증 방식이"
printf '%s\n' "none 이면 null 을 돌려준다(\"Do not issue refresh token to public client\")."
printf '%s\n' "OAuth 2.1 §4.3.1 은 공개 클라이언트에 대한 refresh token 발급을 MAY 로 두고, 발급한다면"
printf '%s\n' "회전 또는 sender-constrained 를 MUST 로 요구한다. 발급하지 않는 쪽은 명세 위반이 아니다."
printf '%s' "대조 — 기밀 클라이언트의 토큰 응답 필드: "
CONF_CODE=$(curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
  --data-urlencode 'response_type=code' --data-urlencode "client_id=$CONFIDENTIAL_CLIENT_ID" \
  --data-urlencode "redirect_uri=$CONFIDENTIAL_REDIRECT_URI" --data-urlencode 'scope=openid profile' \
  --data-urlencode 'state=confidential-state' --data-urlencode "code_challenge=$CHALLENGE" \
  --data-urlencode 'code_challenge_method=S256' --data-urlencode "resource=$MCP" \
  | tr -d '\r' | sed -n 's/^[Ll]ocation: //p' | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
require "CONF_CODE" "$CONF_CODE" "P8. 기밀 클라이언트 대조"
curl -s -u "$CONFIDENTIAL_CLIENT_ID:$CONFIDENTIAL_CLIENT_SECRET" -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=authorization_code' --data-urlencode "code=$CONF_CODE" \
  --data-urlencode "redirect_uri=$CONFIDENTIAL_REDIRECT_URI" --data-urlencode "code_verifier=$VERIFIER" \
  --data-urlencode "resource=$MCP" \
  | sed -n 's/.*{\(.*\)}.*/\1/p' | tr ',' '\n' | sed -n 's/^"\([a-z_]*\)".*/\1/p' | tr '\n' ' '
echo

step "P9. 같은 클라이언트로 다시 인가 — 이미 기록된 동의는 화면을 다시 띄우지 않는다"
public_authorize yes "$PUBLIC_REDIRECT_URI" | tidy | grep -iE '^(HTTP|Location)'

step "P10. 오류: PKCE 없는 인가 요청 (RFC 7636 · MCP MUST)"
public_authorize no "$PUBLIC_REDIRECT_URI" | tidy | grep -iE '^(HTTP|Location)'

step "P11. 루프백 리다이렉트는 포트가 달라도 허용된다 (RFC 8252 §7.3 · OAuth 2.1 §8.4.2)"
# 등록값은 http://127.0.0.1:8123/callback 인데 9999 로 요청해도 코드가 나온다.
# OAuth2AuthorizationCodeRequestAuthenticationValidator.validateRedirectUri 가 호스트가
# 루프백이면 등록 URI 의 포트를 요청 포트로 바꿔 비교한다 — 네이티브 앱이 실행 시점에
# OS 에서 받은 임시 포트를 쓸 수 있게 하기 위한 명세 요구다.
public_authorize yes "http://127.0.0.1:9999/callback" | tidy | grep -iE '^(HTTP|Location)'

step "P11-1. 오류: 루프백이라도 경로가 다르면 거부한다 (리다이렉트하지 않는다)"
public_authorize yes "http://127.0.0.1:8123/not-registered" | tidy | cut -c1-200 | head -8

step "P12. 오류: 틀린 code_verifier (invalid_grant) — 공개 클라이언트의 유일한 가로채기 방어"
new_public_code "P12"
curl -si -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=authorization_code' --data-urlencode "client_id=$PUBLIC_CLIENT_ID" \
  --data-urlencode "code=$CODE" --data-urlencode "redirect_uri=$PUBLIC_REDIRECT_URI" \
  --data-urlencode 'code_verifier=wrong-verifier-wrong-verifier-wrong-verifier-000' \
  --data-urlencode "resource=$MCP" | tidy

step "P13. 오류: 공개 클라이언트에 client_secret 을 실어 보냄 (invalid_client)"
curl -si -u "$PUBLIC_CLIENT_ID:any-secret" -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=authorization_code' --data-urlencode 'code=unused' \
  --data-urlencode "redirect_uri=$PUBLIC_REDIRECT_URI" --data-urlencode "code_verifier=$VERIFIER" \
  --data-urlencode "resource=$MCP" | tidy

step "P14. 대조: 기밀 클라이언트는 동의 화면 없이 곧장 코드로 리다이렉트한다"
curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
  --data-urlencode 'response_type=code' --data-urlencode "client_id=$CONFIDENTIAL_CLIENT_ID" \
  --data-urlencode "redirect_uri=$CONFIDENTIAL_REDIRECT_URI" --data-urlencode 'scope=openid profile' \
  --data-urlencode 'state=confidential-state' --data-urlencode "code_challenge=$CHALLENGE" \
  --data-urlencode 'code_challenge_method=S256' --data-urlencode "resource=$MCP" \
  | tidy | grep -iE '^(HTTP|Location)'

step "P15. 대조: 기밀 클라이언트는 client_id 만으로는 토큰을 받지 못한다 (invalid_client)"
curl -si -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=authorization_code' --data-urlencode "client_id=$CONFIDENTIAL_CLIENT_ID" \
  --data-urlencode 'code=unused' --data-urlencode "redirect_uri=$CONFIDENTIAL_REDIRECT_URI" \
  --data-urlencode "code_verifier=$VERIFIER" --data-urlencode "resource=$MCP" | tidy
