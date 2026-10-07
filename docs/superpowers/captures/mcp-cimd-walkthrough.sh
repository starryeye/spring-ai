#!/usr/bin/env bash
# mcp-cimd practice에서 미리 등록하지 않은 두 client가 CIMD 문서만으로 token을 받는 흐름을 curl로 한 단계씩 기록한다(D 번호).
# ChatGPT형(https://localhost:8172/oauth/client.json)은 private_key_jwt로, Claude형(…/public-client.json)은 none으로 token을 받는다.
# 이 스크립트가 agent 대신 authorization request와 token request를 보낸다. ChatGPT형의 client assertion은 openssl로 서명한다.
# 사용: 저장소 최상위 폴더에서 practice/mcp-cimd/run.sh로 세 서버를 새로 띄운 직후에 실행한다(저장된 consent가 없어야 한다).
#   docs/superpowers/captures/mcp-cimd-walkthrough.sh > 결과.txt
# 출력의 token은 줄인다 — JWT는 앞 20자, refresh_token과 authorization code는 앞 12자 뒤에 "...".
set -uo pipefail

AS=${AS:-http://localhost:9060}
MCP=${MCP:-http://localhost:8171/mcp}
DOCS=${DOCS:-https://localhost:8172}
CERTS=${CERTS:-practice/mcp-cimd/certs}
AS_LOG=${AS_LOG:-practice/mcp-cimd/logs/auth-server.log}
MCP_LOG=${MCP_LOG:-practice/mcp-cimd/logs/shop-mcp-server.log}
CHATGPT="$DOCS/oauth/client.json"
CLAUDE="$DOCS/oauth/public-client.json"
REDIRECT_URI=${REDIRECT_URI:-http://localhost:8170/login/oauth2/code/authserver}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
PROTOCOL_VERSION=2025-11-25
ASSERTION_TYPE=urn:ietf:params:oauth:client-assertion-type:jwt-bearer

# RFC 7636 부록 B의 예시 값이다.
VERIFIER=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
CHALLENGE=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM

step() { printf '\n\n===== %s =====\n' "$1"; }
fail() { printf '\n[오류] %s\n' "$1" >&2; exit 1; }

for command in curl openssl python3; do
  command -v "$command" > /dev/null || fail "$command가 없다"
done
[ -f "$CERTS/client-metadata.crt" ] || fail "$CERTS/client-metadata.crt가 없다. practice/mcp-cimd/run.sh를 먼저 실행한다"

WORK=$(mktemp -d)
JAR="$WORK/cookies"
trap 'rm -rf "$WORK"' EXIT

tidy() {
  tr -d '\r' \
    | grep -vE '^(X-Content-Type-Options|X-XSS-Protection|X-Frame-Options|Expires|Date|Keep-Alive|Connection|Cache-Control|Pragma|Vary):' \
    | sed -E \
        -e 's/(eyJ[A-Za-z0-9_-]{17})[A-Za-z0-9_.-]+/\1.../g' \
        -e 's/("refresh_token":"[^"]{12})[^"]*"/\1..."/g' \
        -e 's/(refresh_token=[^&[:space:]]{12})[^&[:space:]]*/\1.../g' \
        -e 's/([?&]code=[^&[:space:]]{12})[^&[:space:]]*/\1.../g' \
        -e 's/(^  code=[^[:space:]]{12})[^[:space:]]*/\1.../g'
}

payload() {
  local segment="$1" pad
  pad=$(( (4 - ${#segment} % 4) % 4 ))
  [ "$pad" -gt 0 ] && segment="$segment$(printf '=%.0s' $(seq 1 $pad))"
  printf '%s' "$segment" | tr '_-' '/+' | base64 -d 2>/dev/null
  echo
}

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

location_of() { printf '%s' "$1" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p'; }

json_field() { python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1"; }

# login 화면을 열어 CSRF token을 읽고 login한다. JAR에 session cookie가 남는다.
login() {
  local username="$1" form csrf
  form=$(curl -s -c "$JAR" -b "$JAR" "$AS/login")
  csrf=$(printf '%s' "$form" | grep -o '<input[^>]*name="_csrf"[^>]*>' | head -1 | grep -o 'value="[^"]*"' | sed 's/^value="//;s/"$//')
  [ -n "$csrf" ] || fail "CSRF token을 찾지 못했다"
  curl -s -o /dev/null -w 'POST /login → HTTP %{http_code}\n' -c "$JAR" -b "$JAR" -X POST "$AS/login" \
    --data-urlencode "username=$username" --data-urlencode "password=$LOGIN_PASSWORD" --data-urlencode "_csrf=$csrf"
}

# $1 client_id, $2 scope, $3 redirect_uri(생략하면 문서의 주소).
# consent 화면으로 가면 화면의 핵심 줄을 보여 주고 PAGE_STATE를, client의 redirect 주소로 가면 CODE를 채운다.
authorize() {
  local client_id="$1" scope="$2" redirect="${3:-$REDIRECT_URI}" headers location page
  headers=$(curl -s -D - -o /dev/null -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
    --data-urlencode response_type=code --data-urlencode "client_id=$client_id" \
    --data-urlencode "redirect_uri=$redirect" --data-urlencode "scope=$scope" \
    --data-urlencode state=state-1 --data-urlencode "code_challenge=$CHALLENGE" \
    --data-urlencode code_challenge_method=S256 --data-urlencode "resource=$MCP")
  printf '%s\n' "$headers" | grep -iE '^(HTTP/|location:)' | tidy
  STATUS=$(printf '%s\n' "$headers" | head -1 | awk '{print $2}')
  location=$(location_of "$headers")
  PAGE_STATE=""
  CODE=""
  case "$location" in
    *"/oauth2/consent"*)
      page="$WORK/consent.html"
      curl -s -o "$page" -c "$JAR" -b "$JAR" "$location"
      echo "consent 화면(GET /oauth2/consent)의 핵심 줄:"
      python3 - "$page" <<'EOF'
import re, sys
page = open(sys.argv[1], encoding='utf-8').read()
for line in re.sub(r'><', '>\n<', page).splitlines():
    if re.search(r'<h1>|<p>client 문서|<p>허락하면|<strong>|name="scope"|이미 허락한|checked disabled', line):
        print(line)
EOF
      PAGE_STATE=$(grep -o 'name="state" value="[^"]*"' "$page" | head -1 | sed 's/.*value="//;s/"$//')
      ;;
    *)
      CODE=$(printf '%s' "$location" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
      ;;
  esac
}

# $1 client_id, 나머지는 체크할 scope. consent를 제출하고 CODE를 채운다.
consent() {
  local client_id="$1" headers
  shift
  local args=(--data-urlencode "client_id=$client_id" --data-urlencode "state=$PAGE_STATE")
  for s in "$@"; do args+=(--data-urlencode "scope=$s"); done
  echo "체크한 scope: ${*:-(없음)}"
  headers=$(curl -s -D - -o /dev/null -c "$JAR" -b "$JAR" -X POST "$AS/oauth2/authorize" "${args[@]}")
  printf '%s\n' "$headers" | grep -iE '^(HTTP/|location:)' | tidy
  CODE=$(location_of "$headers" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
}

# $1 서명 key(PEM). ChatGPT형의 client assertion(RFC 7523)을 만든다.
# iss·sub는 client_id, aud는 token endpoint, kid는 agent가 올린 JWKS의 값이다.
assertion() {
  local key="$1" now header body signature
  now=$(date +%s)
  header=$(printf '{"alg":"RS256","kid":"%s","typ":"JWT"}' "$KID" | b64url)
  body=$(printf '{"iss":"%s","sub":"%s","aud":"%s","jti":"%s","iat":%d,"exp":%d}' \
    "$CHATGPT" "$CHATGPT" "$AS/oauth2/token" "$(python3 -c 'import uuid; print(uuid.uuid4())')" "$now" "$((now + 60))" | b64url)
  signature=$(printf '%s.%s' "$header" "$body" | openssl dgst -sha256 -sign "$key" -binary | b64url)
  printf '%s.%s.%s' "$header" "$body" "$signature"
}

# $1 기대하는 HTTP 상태, 나머지는 name=value. form을 한 줄에 하나씩 보여 주고 token endpoint로 보낸다. 응답 본문은 BODY에 둔다.
token_request() {
  local expected="$1" pair response status
  shift
  local args=()
  echo "POST /oauth2/token"
  for pair in "$@"; do
    printf '  %s\n' "$pair" | tidy
    args+=(--data-urlencode "$pair")
  done
  response=$(curl -s -D "$WORK/headers" -w '\n%{http_code}' "$AS/oauth2/token" "${args[@]}")
  status=$(printf '%s' "$response" | tail -1)
  BODY=$(printf '%s' "$response" | sed '$d')
  echo "→ HTTP $status"
  grep -i '^WWW-Authenticate:' "$WORK/headers" | tidy
  printf '%s\n' "$BODY" | tidy
  [ "$status" = "$expected" ] || fail "token request가 $expected가 아니라 $status다"
}

access_token_payload() {
  TOKEN=$(printf '%s' "$BODY" | json_field access_token)
  [ -n "$TOKEN" ] || fail "access_token이 없다"
  echo "access token payload:"
  payload "$(printf '%s' "$TOKEN" | cut -d. -f2)"
}

step "D1. Authorization Server metadata — CIMD 표시와 받는 인증 방식"
curl -s "$AS/.well-known/oauth-authorization-server" | python3 -c '
import json, sys
m = json.load(sys.stdin)
keys = ["issuer", "client_id_metadata_document_supported", "token_endpoint_auth_methods_supported",
        "token_endpoint_auth_signing_alg_values_supported", "code_challenge_methods_supported"]
print(json.dumps({k: m.get(k) for k in keys}, ensure_ascii=False, indent=2))
print("dpop_signing_alg_values_supported 있음:", "dpop_signing_alg_values_supported" in m)
'

step "D2. agent가 https://localhost:8172에 올린 두 문서와 JWKS"
for path in /oauth/client.json /oauth/public-client.json /oauth/jwks.json; do
  echo "--- GET $DOCS$path"
  curl -s -i --cacert "$CERTS/client-metadata.crt" "$DOCS$path" | tr -d '\r' \
    | grep -vE '^(Date|Content-length):' | sed -E 's/("n":"[^"]{20})[^"]*"/\1..."/'
  echo
done
KID=$(curl -s --cacert "$CERTS/client-metadata.crt" "$DOCS/oauth/jwks.json" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["keys"][0]["kid"])')
[ -n "$KID" ] || fail "JWKS에서 kid를 읽지 못했다"

# agent의 서명 key를 PEM으로 꺼낸다. OpenSSL 3에서 옛 PKCS12면 -legacy가 필요할 수 있다.
openssl pkcs12 -in "$CERTS/client-signing.p12" -nocerts -nodes -passin pass:changeit 2>/dev/null \
  | openssl pkey -out "$WORK/agent-key.pem" 2>/dev/null \
  || openssl pkcs12 -legacy -in "$CERTS/client-signing.p12" -nocerts -nodes -passin pass:changeit 2>/dev/null \
  | openssl pkey -out "$WORK/agent-key.pem" 2>/dev/null
[ -s "$WORK/agent-key.pem" ] || fail "서명 key를 꺼내지 못했다"
# 문서의 jwks_uri에 없는 key다. 실패 단계에서 쓴다.
openssl genrsa -out "$WORK/other-key.pem" 2048 2>/dev/null || fail "다른 key를 만들지 못했다"

step "D3. ChatGPT형: user login → authorization request → consent 화면 → 허락"
login user
authorize "$CHATGPT" "openid products:read"
[ -n "$PAGE_STATE" ] || fail "처음 보는 client인데 consent 화면이 나오지 않았다"
consent "$CHATGPT" products:read
[ -n "$CODE" ] || fail "authorization code가 없다"

step "D4. ChatGPT형 token request — private_key_jwt(client assertion)"
token_request 200 grant_type=authorization_code "code=$CODE" "redirect_uri=$REDIRECT_URI" "code_verifier=$VERIFIER" \
  "resource=$MCP" "client_id=$CHATGPT" "client_assertion_type=$ASSERTION_TYPE" \
  "client_assertion=$(assertion "$WORK/agent-key.pem")"
access_token_payload
CHATGPT_TOKEN=$TOKEN
CHATGPT_REFRESH=$(printf '%s' "$BODY" | json_field refresh_token)
[ -n "$CHATGPT_REFRESH" ] || fail "ChatGPT형 refresh_token이 없다"

step "D5. ChatGPT형 token으로 MCP tools/list"
curl -s -i -X POST "$MCP" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION" -H "Authorization: Bearer $CHATGPT_TOKEN" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | tidy | head -1
echo "tools/list가 200이면 MCP Server가 token의 aud와 서명을 받아들인 것이다."
if [ -f "$MCP_LOG" ]; then
  echo "MCP Server 로그의 마지막 tools/list 줄:"
  # MCP Server의 출력은 gradle을 거쳐 파일에 닿아서 응답보다 조금 늦을 수 있다.
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    line=$(grep 'tools/list' "$MCP_LOG" | tail -1)
    [ -n "$line" ] && break
    sleep 0.5
  done
  printf '%s\n' "${line:-(아직 기록되지 않았다)}"
fi

step "D6. 실패: 문서의 jwks_uri에 없는 key로 서명한 assertion → 401 invalid_client"
authorize "$CHATGPT" "openid products:read"
[ -n "$CODE" ] || fail "허락한 scope인데 consent 화면이 다시 나왔다"
token_request 401 grant_type=authorization_code "code=$CODE" "redirect_uri=$REDIRECT_URI" "code_verifier=$VERIFIER" \
  "resource=$MCP" "client_id=$CHATGPT" "client_assertion_type=$ASSERTION_TYPE" \
  "client_assertion=$(assertion "$WORK/other-key.pem")"

step "D7. 실패: private_key_jwt client가 assertion 없이 client_id와 code_verifier만 보냄 → 401 invalid_client"
authorize "$CHATGPT" "openid products:read"
token_request 401 grant_type=authorization_code "code=$CODE" "redirect_uri=$REDIRECT_URI" "code_verifier=$VERIFIER" \
  "resource=$MCP" "client_id=$CHATGPT"

step "D8. 실패: 문서에 없는 redirect 주소 → redirect 없이 400"
authorize "$CHATGPT" "openid products:read" "http://localhost:8170/elsewhere"
[ "$STATUS" = 400 ] || fail "문서에 없는 redirect 주소가 400이 아니라 $STATUS다"
[ -z "$CODE" ] || fail "문서에 없는 redirect 주소로 code가 나갔다"

step "D9. ChatGPT형 refresh — assertion을 붙이고, 새 refresh token을 받는다"
token_request 200 grant_type=refresh_token "refresh_token=$CHATGPT_REFRESH" "resource=$MCP" "client_id=$CHATGPT" \
  "client_assertion_type=$ASSERTION_TYPE" "client_assertion=$(assertion "$WORK/agent-key.pem")"
[ "$(printf '%s' "$BODY" | json_field refresh_token)" != "$CHATGPT_REFRESH" ] || fail "refresh token이 바뀌지 않았다"

step "D10. Claude형: authorization request → consent 화면 → 허락"
authorize "$CLAUDE" "openid products:read"
[ -n "$PAGE_STATE" ] || fail "Claude형인데 consent 화면이 나오지 않았다"
consent "$CLAUDE" products:read
[ -n "$CODE" ] || fail "authorization code가 없다"

step "D11. Claude형 token request — none(client_id와 code_verifier만), refresh token도 온다"
token_request 200 grant_type=authorization_code "code=$CODE" "redirect_uri=$REDIRECT_URI" "code_verifier=$VERIFIER" \
  "resource=$MCP" "client_id=$CLAUDE"
access_token_payload
CLAUDE_REFRESH=$(printf '%s' "$BODY" | json_field refresh_token)
[ -n "$CLAUDE_REFRESH" ] || fail "Claude형 refresh_token이 없다"

step "D12. Claude형 refresh — 새 refresh token을 받는다(rotation)"
token_request 200 grant_type=refresh_token "refresh_token=$CLAUDE_REFRESH" "resource=$MCP" "client_id=$CLAUDE"
CLAUDE_REFRESH2=$(printf '%s' "$BODY" | json_field refresh_token)
[ -n "$CLAUDE_REFRESH2" ] && [ "$CLAUDE_REFRESH2" != "$CLAUDE_REFRESH" ] || fail "refresh token이 바뀌지 않았다"

step "D13. Claude형 옛 refresh token을 다시 씀 → 400 invalid_grant"
token_request 400 grant_type=refresh_token "refresh_token=$CLAUDE_REFRESH" "resource=$MCP" "client_id=$CLAUDE"

step "D14. Claude형은 방금 허락했어도 다시 consent 화면 — public client의 consent는 저장하지 않는다"
authorize "$CLAUDE" "openid products:read"
[ -n "$PAGE_STATE" ] || fail "Claude형의 두 번째 요청에 consent 화면이 나오지 않았다"

step "D15. Authorization Server 로그 — 문서를 가져오고 cache에서 꺼낸 기록"
if [ -f "$AS_LOG" ]; then
  # 로그가 파일에 닿기를 잠깐 기다린다. 날짜·thread·logger 이름을 떼고 메시지만 남기며, 연속으로 같은 줄은 앞의 숫자(횟수)로 묶는다.
  sleep 1
  grep 'client 문서를' "$AS_LOG" | sed -E 's/^.* : //' | uniq -c | sed -E 's/^ +//'
else
  echo "(로그 파일이 없다: $AS_LOG)"
fi
