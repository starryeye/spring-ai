#!/usr/bin/env bash
# official agent의 callback이 iss를 확인해 login을 거절할 때의 응답과 로그를 기록한다.
# mcp-authorization-supplement.sh의 S17~S19와 같은 요청을 보내고, agent 로그에서 자세한 이유를 함께 모은다.
# 사용: practice/mcp-security-authn-official을 run.sh로 띄운 뒤 저장소 최상위 폴더에서
#   docs/superpowers/captures/mcp-login-failure.sh > 결과.txt
set -uo pipefail

AGENT=${AGENT:-http://localhost:8110}
REDIRECT_URI=${REDIRECT_URI:-$AGENT/login/oauth2/code/authserver}
AGENT_LOG=${AGENT_LOG:-practice/mcp-security-authn-official/logs/shop-agent.log}

AGENT_JAR=$(mktemp)
trap 'rm -f "$AGENT_JAR"' EXIT

step() { printf '\n\n===== %s =====\n' "$1"; }
tidy() {
  tr -d '\r' \
    | grep -vE '^(X-Content-Type-Options|X-XSS-Protection|X-Frame-Options|Expires|Date|Keep-Alive|Connection):'
}
location_of() { printf '%s' "$1" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p'; }
query_param() { printf '%s' "$1" | sed -n "s/.*[?&]$2=\([^&]*\).*/\1/p"; }
fail() { printf '\n[오류] %s\n' "$1" >&2; exit 1; }

# agent가 만든 authorization request의 state를 받는다. 첫 요청이면 agent가 이때 discovery를 한다.
agent_state() {
  local response
  response=$(curl -si -c "$AGENT_JAR" -b "$AGENT_JAR" "$AGENT/oauth2/authorization/authserver")
  query_param "$(location_of "$response")" state
}

printf '# login 실패 관측 — %s (%s)\n' "$(date +%F)" "$(basename "$0")"
LOG_START=$(wc -l < "$AGENT_LOG" 2>/dev/null || echo 0)

step "L1. 조작한 iss로 agent callback (code를 교환하지 않고 거절)"
STATE=$(agent_state)
[ -n "$STATE" ] || fail "agent의 authorization request에서 state를 찾지 못했다"
curl -si -c "$AGENT_JAR" -b "$AGENT_JAR" \
  "$REDIRECT_URI?code=forged-code&state=$STATE&iss=http%3A%2F%2Fevil.example" | tidy

step "L2. iss 없이 agent callback (Authorization Server가 iss 지원을 알렸으므로 거절)"
STATE=$(agent_state)
[ -n "$STATE" ] || fail "agent의 authorization request에서 state를 찾지 못했다"
curl -si -c "$AGENT_JAR" -b "$AGENT_JAR" "$REDIRECT_URI?code=forged-code&state=$STATE" | tidy

step "L3. agent 로그 — 응답에 쓰지 않은 자세한 이유"
sleep 1
tail -n +"$((LOG_START + 1))" "$AGENT_LOG" | grep -o '로그인 실패: .*' || echo "(로그에서 찾지 못했다: $AGENT_LOG)"
