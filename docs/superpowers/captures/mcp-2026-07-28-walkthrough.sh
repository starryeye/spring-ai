#!/usr/bin/env bash
# mcp-2026-07-28 practice에서 2026-07-28 형식의 MCP 요청과 응답을 curl로 한 단계씩 기록한다(M 번호).
# initialize 없이 요청마다 header와 params._meta로 버전·client 정보를 밝힌다.
# MCP Server는 token의 scope와 header·본문의 일치를 요청마다 본다.
# 조회 token은 ChatGPT형(private_key_jwt)으로, 쓰기 token은 Claude형(none)으로 받는다.
# 둘 다 CIMD 문서가 client_id다.
# 이 스크립트가 agent 대신 authorization request, token request, MCP 요청을 보낸다.
# ChatGPT형의 client assertion은 openssl로 서명한다.
# 기대한 HTTP 상태와 다르면 그 단계에 [기대와 다름]을 남기고 끝까지 간다.
# 결과를 맞추려고 요청을 바꾸지 않는다.
# 기대를 미리 알 수 없는 요청은 기대 자리에 "기록만"을 적고 받은 응답을 그대로 남긴다.
# 사용: 저장소 최상위 폴더에서 practice/mcp-2026-07-28/run.sh로 세 서버를 새로 띄운 직후에 실행한다(저장된 consent가 없어야 한다).
#   docs/superpowers/captures/mcp-2026-07-28-walkthrough.sh > 결과.txt
# 출력의 token은 줄인다 — JWT는 앞 20자, refresh_token과 authorization code는 앞 12자 뒤에 "...".
set -uo pipefail

AS=${AS:-http://localhost:9070}
MCP=${MCP:-http://localhost:8181/mcp}
DOCS=${DOCS:-https://localhost:8182}
CERTS=${CERTS:-practice/mcp-2026-07-28/certs}
MCP_LOG=${MCP_LOG:-practice/mcp-2026-07-28/logs/shop-mcp-server.log}
CHATGPT="$DOCS/oauth/client.json"
CLAUDE="$DOCS/oauth/public-client.json"
REDIRECT_URI=${REDIRECT_URI:-http://localhost:8180/login/oauth2/code/authserver}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
PROTOCOL_VERSION=2026-07-28
ASSERTION_TYPE=urn:ietf:params:oauth:client-assertion-type:jwt-bearer

# RFC 7636 부록 B의 예시 값이다.
VERIFIER=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
CHALLENGE=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM

CURRENT=""
RESULTS=()
MISMATCH=0

step() { CURRENT="${1%%.*}"; printf '\n\n===== %s =====\n' "$1"; }
fail() { printf '\n[오류] %s\n' "$1" >&2; exit 1; }

for command in curl openssl python3; do
  command -v "$command" > /dev/null || fail "$command가 없다"
done
[ -f "$CERTS/client-metadata.crt" ] || fail "$CERTS/client-metadata.crt가 없다. practice/mcp-2026-07-28/run.sh를 먼저 실행한다"

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

# login 화면을 열어 CSRF token을 읽고 login한다.
# JAR에 session cookie가 남는다.
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

# $1 client_id, 나머지는 체크할 scope.
# consent를 제출하고 CODE를 채운다.
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

# $1 서명 key(PEM), $2 aud(없으면 issuer).
# ChatGPT형의 client assertion(RFC 7523)을 만든다.
# iss·sub는 client_id, aud는 RFC 7523bis대로 Authorization Server의 issuer 하나, kid는 agent가 올린 JWKS의 값이다.
# typ은 7523bis가 권하는 client-authentication+jwt다.
assertion() {
  local key="$1" aud="${2:-$AS}" now header body signature
  now=$(date +%s)
  header=$(printf '{"alg":"RS256","kid":"%s","typ":"client-authentication+jwt"}' "$KID" | b64url)
  body=$(printf '{"iss":"%s","sub":"%s","aud":"%s","jti":"%s","iat":%d,"exp":%d}' \
    "$CHATGPT" "$CHATGPT" "$aud" "$(python3 -c 'import uuid; print(uuid.uuid4())')" "$now" "$((now + 60))" | b64url)
  signature=$(printf '%s.%s' "$header" "$body" | openssl dgst -sha256 -sign "$key" -binary | b64url)
  printf '%s.%s.%s' "$header" "$body" "$signature"
}

# $1 기대하는 HTTP 상태, 나머지는 name=value.
# form을 한 줄에 하나씩 보여 주고 token endpoint로 보낸다.
# 응답 본문은 BODY에 둔다.
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

# 2026-07-28 형식의 JSON-RPC 요청 본문을 한 줄로 만든다.
# $1 id, $2 method, $3 params(JSON object, 생략하면 비어 있다).
# params._meta에는 agent가 쓰는 버전·client capability·client 정보를 넣는다.
rpc_body() {
  local params="${3:-}"
  [ -n "$params" ] || params='{}'
  python3 -c '
import json, sys
params = json.loads(sys.argv[3])
params["_meta"] = {
    "io.modelcontextprotocol/protocolVersion": "2026-07-28",
    "io.modelcontextprotocol/clientCapabilities": {},
    "io.modelcontextprotocol/clientInfo": {"name": "modern-shop-agent", "version": "0.0.1"},
}
print(json.dumps({"jsonrpc": "2.0", "id": int(sys.argv[1]), "method": sys.argv[2], "params": params},
                 ensure_ascii=False, separators=(",", ":")))
' "$1" "$2" "$params"
}

pretty() {
  python3 -c '
import json, sys
raw = sys.stdin.read()
try:
    print(json.dumps(json.loads(raw), ensure_ascii=False, indent=2))
except ValueError:
    print(raw.rstrip())
'
}

# 응답 본문을 보여 준다.
# BODY_VIEW=tools면 tools/list 결과에서 updateStock 정의만 모두 보이고 다른 tool은 이름만 남긴다.
# result의 나머지 필드는 그대로 둔다.
render_body() {
  python3 - "$1" "${BODY_VIEW:-full}" <<'EOF'
import json, sys
raw = open(sys.argv[1], encoding='utf-8').read()
if not raw.strip():
    print("(본문 없음)")
    sys.exit()
try:
    doc = json.loads(raw)
except ValueError:
    print(raw.rstrip())
    sys.exit()
if sys.argv[2] == 'tools':
    result = doc.get('result', {})
    tools = result.get('tools', [])
    names = [tool.get('name') for tool in tools]
    # updateStock의 정의만 그대로 두고 다른 tool은 이름만 남긴다.
    shown_tools = [tool if tool.get('name') == 'updateStock' else '%s (정의 생략)' % tool.get('name') for tool in tools]
    shown = {key: (shown_tools if key == 'tools' else value) for key, value in result.items()}
    print(json.dumps({**{k: v for k, v in doc.items() if k != 'result'}, 'result': shown},
                     ensure_ascii=False, indent=2))
    print("tool %d개: %s" % (len(names), ', '.join(names)))
    print("(updateStock의 정의만 모두 보이고, 나머지는 이름만 보인다)")
else:
    print(json.dumps(doc, ensure_ascii=False, indent=2))
EOF
}

# MCP Server의 출력은 gradle을 거쳐 파일에 닿아서 응답보다 조금 늦을 수 있다.
# $1 grep 패턴.
# 가장 마지막으로 맞는 줄의 메시지만 보여 준다.
server_log_line() {
  local line=""
  [ -f "$MCP_LOG" ] || { echo "(로그 파일이 없다: $MCP_LOG)"; return; }
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    line=$(grep -- "$1" "$MCP_LOG" | tail -1)
    [ -n "$line" ] && break
    sleep 0.5
  done
  if [ -n "$line" ]; then
    printf 'MCP Server 로그: %s\n' "$(printf '%s' "$line" | sed -E 's/^.* : //')"
  else
    echo "MCP Server 로그: (아직 기록되지 않았다)"
  fi
}

# $1 기대하는 HTTP 상태("기록만"이면 비교하지 않는다), $2 token(비우면 Authorization을 붙이지 않는다),
# $3 Mcp-Method header(비우면 붙이지 않는다), $4 Mcp-Name header(비우면 붙이지 않는다),
# $5 MCP-Protocol-Version header(비우면 붙이지 않는다), $6 본문.
# 보내는 요청과 받은 응답을 그대로 보여 준다.
# 기대와 다른 상태면 그 사실을 남긴 채 계속 간다.
# 응답은 STATUS, BODY, HEADERS에 둔다.
mcp_request() {
  local expected="$1" token="$2" method="$3" name="$4" version="$5" body="$6"
  local args=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
  echo "POST $MCP"
  echo "Content-Type: application/json"
  echo "Accept: application/json, text/event-stream"
  if [ -n "$version" ]; then
    args+=(-H "MCP-Protocol-Version: $version")
    echo "MCP-Protocol-Version: $version"
  fi
  if [ -n "$method" ]; then
    args+=(-H "Mcp-Method: $method")
    echo "Mcp-Method: $method"
  fi
  if [ -n "$name" ]; then
    args+=(-H "Mcp-Name: $name")
    echo "Mcp-Name: $name"
  fi
  if [ -n "$token" ]; then
    args+=(-H "Authorization: Bearer $token")
    printf 'Authorization: Bearer %s\n' "$token" | tidy
  fi
  echo
  printf '%s\n' "$body" | pretty
  STATUS=$(curl -s -D "$WORK/headers" -o "$WORK/body" -w '%{http_code}' -X POST "$MCP" "${args[@]}" --data-binary "$body")
  HEADERS=$(tr -d '\r' < "$WORK/headers")
  BODY=$(cat "$WORK/body")
  echo
  echo "→ HTTP $STATUS"
  printf '%s\n' "$HEADERS" | grep -iE '^(www-authenticate|content-type|retry-after):' | tidy
  render_body "$WORK/body"
  if [ "$expected" = "기록만" ]; then
    echo "(기대한 상태가 없다: 받은 응답을 그대로 기록한다)"
  elif [ "$STATUS" != "$expected" ]; then
    echo "[기대와 다름] 기대한 HTTP $expected, 받은 HTTP $STATUS"
    MISMATCH=1
  fi
  RESULTS+=("$CURRENT|$expected|$STATUS")
}

step "M1. 실패: token 없는 server/discover → 401과 WWW-Authenticate의 resource_metadata"
mcp_request 401 "" server/discover "" "$PROTOCOL_VERSION" "$(rpc_body 1 server/discover)"
PRM_URL=$(printf '%s\n' "$HEADERS" | sed -n 's/^[Ww][Ww][Ww]-[Aa]uthenticate:.*resource_metadata="\([^"]*\)".*/\1/p')
[ -n "$PRM_URL" ] || fail "WWW-Authenticate에서 resource_metadata를 읽지 못했다"
echo
echo "--- GET $PRM_URL (challenge가 알려 준 Protected Resource Metadata)"
curl -s -D "$WORK/headers" -o "$WORK/body" -w '→ HTTP %{http_code}\n' "$PRM_URL"
grep -iE '^content-type:' "$WORK/headers" | tidy
render_body "$WORK/body"

# agent가 올린 JWKS에서 kid를 읽고, agent의 서명 key를 PEM으로 꺼낸다.
# OpenSSL 3에서 옛 PKCS12면 -legacy가 필요할 수 있다.
KID=$(curl -s --cacert "$CERTS/client-metadata.crt" "$DOCS/oauth/jwks.json" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["keys"][0]["kid"])')
[ -n "$KID" ] || fail "JWKS에서 kid를 읽지 못했다"
openssl pkcs12 -in "$CERTS/client-signing.p12" -nocerts -nodes -passin pass:changeit 2>/dev/null \
  | openssl pkey -out "$WORK/agent-key.pem" 2>/dev/null \
  || openssl pkcs12 -legacy -in "$CERTS/client-signing.p12" -nocerts -nodes -passin pass:changeit 2>/dev/null \
  | openssl pkey -out "$WORK/agent-key.pem" 2>/dev/null
[ -s "$WORK/agent-key.pem" ] || fail "서명 key를 꺼내지 못했다"

step "준비 1. ChatGPT형으로 조회 token을 받는다 — user login → authorization request(products:read) → consent → private_key_jwt"
login user
authorize "$CHATGPT" "openid products:read"
[ -n "$PAGE_STATE" ] || fail "처음 보는 client인데 consent 화면이 나오지 않았다"
consent "$CHATGPT" products:read
[ -n "$CODE" ] || fail "authorization code가 없다"
token_request 200 grant_type=authorization_code "code=$CODE" "redirect_uri=$REDIRECT_URI" "code_verifier=$VERIFIER" \
  "resource=$MCP" "client_id=$CHATGPT" "client_assertion_type=$ASSERTION_TYPE" \
  "client_assertion=$(assertion "$WORK/agent-key.pem")"
access_token_payload
READ_TOKEN=$TOKEN

step "M2. ChatGPT형 조회 token으로 server/discover → 200, 서버가 지원하는 버전과 정보"
mcp_request 200 "$READ_TOKEN" server/discover "" "$PROTOCOL_VERSION" "$(rpc_body 2 server/discover)"

step "M3. ChatGPT형 조회 token으로 tools/list → 200, ttlMs와 cacheScope, updateStock의 정의"
BODY_VIEW=tools
mcp_request 200 "$READ_TOKEN" tools/list "" "$PROTOCOL_VERSION" "$(rpc_body 3 tools/list)"
BODY_VIEW=full

step "M4. ChatGPT형 조회 token으로 tools/call searchProducts → 200, 검색 결과"
mcp_request 200 "$READ_TOKEN" tools/call searchProducts "$PROTOCOL_VERSION" \
  "$(rpc_body 4 tools/call '{"name":"searchProducts","arguments":{"keyword":"노트북"}}')"

step "M5. 실패: 조회 token으로 updateStock → 403, challenge의 scope=\"products:write\""
mcp_request 403 "$READ_TOKEN" tools/call updateStock "$PROTOCOL_VERSION" \
  "$(rpc_body 5 tools/call '{"name":"updateStock","arguments":{"productId":"p1","quantity":10}}')"
server_log_line 'scope 부족'

step "M6. 실패: Mcp-Name은 searchProducts인데 본문의 name은 updateStock → 400"
mcp_request 400 "$READ_TOKEN" tools/call searchProducts "$PROTOCOL_VERSION" \
  "$(rpc_body 6 tools/call '{"name":"updateStock","arguments":{"productId":"p1","quantity":10}}')"

step "M7. 실패: 본문의 name이 문자열이 아니라 숫자 1 → 400"
mcp_request 400 "$READ_TOKEN" tools/call searchProducts "$PROTOCOL_VERSION" \
  "$(rpc_body 7 tools/call '{"name":1,"arguments":{"keyword":"노트북"}}')"

step "M8. 실패: 옛 형식 client(2025-11-25)의 initialize는 2026-07-28 header가 없어서 → 400, 2026-07-28 형식의 initialize는 어떻게 답하는지도 기록한다"
echo "--- 옛 형식 client가 보내는 initialize: MCP-Protocol-Version은 initialize를 마친 뒤에 붙이므로 없고, Mcp-Method도 없다"
CURRENT="M8 첫 번째 요청"
mcp_request 400 "$READ_TOKEN" "" "" "" \
  '{"jsonrpc":"2.0","id":8,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"modern-shop-agent","version":"0.0.1"}}}'
echo
echo "--- 같은 initialize method를 2026-07-28 header와 _meta로 보낸다. 서버가 method 자체를 어떻게 답하는지 본다"
CURRENT="M8 두 번째 요청"
mcp_request 기록만 "$READ_TOKEN" initialize "" "$PROTOCOL_VERSION" "$(rpc_body 8 initialize)"

step "준비 2. Claude형으로 products:read와 products:write를 함께 요청해 쓰기 token을 받는다 — Claude형은 ChatGPT형과 다른 client라서 consent를 새로 받는다"
echo "M5의 403은 products:write가 모자라다고 알렸다."
echo "모든 MCP 요청에 products:read도 필요하므로 둘을 함께 요청한다."
echo "Claude형의 client_id는 ChatGPT형과 달라서 consent 화면에 두 scope가 모두 새 항목으로 나온다."
login user
authorize "$CLAUDE" "openid products:read products:write"
[ -n "$PAGE_STATE" ] || fail "Claude형인데 consent 화면이 나오지 않았다"
consent "$CLAUDE" products:read products:write
[ -n "$CODE" ] || fail "authorization code가 없다"
token_request 200 grant_type=authorization_code "code=$CODE" "redirect_uri=$REDIRECT_URI" "code_verifier=$VERIFIER" \
  "resource=$MCP" "client_id=$CLAUDE"
access_token_payload
WRITE_TOKEN=$TOKEN

step "M9. Claude형 쓰기 token으로 updateStock → 200, 재고를 바꿨다"
mcp_request 200 "$WRITE_TOKEN" tools/call updateStock "$PROTOCOL_VERSION" \
  "$(rpc_body 9 tools/call '{"name":"updateStock","arguments":{"productId":"p1","quantity":10}}')"
server_log_line 'updateStock 호출'
# M5~M8은 tool까지 가지 못했다.
# 그러므로 서버를 새로 띄운 뒤의 호출 줄은 M9의 하나뿐이어야 한다.
if [ -f "$MCP_LOG" ]; then
  echo "MCP Server 로그에서 updateStock 호출 줄 수: $(grep -c 'updateStock 호출' "$MCP_LOG" || true)"
else
  echo "MCP Server 로그 파일을 찾지 못했다($MCP_LOG): updateStock 호출 줄 수를 세지 못했다"
fi

step "요약. 단계마다 기대한 HTTP 상태와 받은 상태"
for row in "${RESULTS[@]}"; do
  IFS='|' read -r label expected actual <<< "$row"
  if [ "$expected" = "$actual" ] || [ "$expected" = "기록만" ]; then mark=""; else mark="  [기대와 다름]"; fi
  printf '%s: 기대 %s, 실제 %s%s\n' "$label" "$expected" "$actual" "$mark"
done
exit "$MISMATCH"
