#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p logs

# agent가 어느 제품처럼 붙을지 고른다. chatgpt면 private_key_jwt, claude면 none(public client)이다.
CLIENT_TYPE="${1:-chatgpt}"
case "$CLIENT_TYPE" in
  chatgpt|claude) ;;
  *)
    echo "[실패] client type은 chatgpt나 claude입니다: $CLIENT_TYPE" >&2
    exit 1
    ;;
esac

# Java 21 확보 (시스템 기본은 17). sdkman 의 `current` 심볼릭 링크는 21 이 아닐 수
# 있으므로(이 저장소 개발 환경은 17), candidates 디렉터리에서 21.* 후보를 직접 찾는다.
if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q '"21'; then
  JAVA21_HOME=$(find "$HOME/.sdkman/candidates/java" -maxdepth 1 -type d -name '21.*' 2>/dev/null | sort -V | tail -1)
  if [ -n "$JAVA21_HOME" ]; then
    export JAVA_HOME="$JAVA21_HOME"
  fi
fi
if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q '"21'; then
  echo "[실패] Java 21 을 찾지 못했습니다. \$HOME/.sdkman/candidates/java 아래에 21.x 후보를 설치하거나 JAVA_HOME 을 Java 21 로 직접 지정하세요." >&2
  exit 1
fi
echo "JAVA_HOME=${JAVA_HOME}"

# ollama 준비
if ! curl -sf http://localhost:11434/api/tags > /dev/null 2>&1; then
  echo "ollama 를 시작합니다..."
  ( nohup ollama serve > logs/ollama.log 2>&1 < /dev/null & disown 2>/dev/null || true )
  for _ in $(seq 1 30); do
    curl -sf http://localhost:11434/api/tags > /dev/null 2>&1 && break
    sleep 1
  done
fi
if ! ollama list 2>/dev/null | grep -q 'qwen3:8b'; then
  echo "qwen3:8b 모델을 내려받습니다 (시간이 걸립니다)..."
  ollama pull qwen3:8b
fi

# agent가 client 문서를 올리는 https://localhost:8172의 self-signed 인증서와, private_key_jwt에 쓰는 서명 key다.
# 없을 때만 만든다. 비밀 key가 들어 있으므로 certs/는 commit하지 않는다(.gitignore).
make_certs() {
  local keytool="$JAVA_HOME/bin/keytool"
  mkdir -p certs
  if [ ! -f certs/client-metadata-tls.p12 ]; then
    echo "  [인증서] certs/client-metadata-tls.p12 (CN=localhost, self-signed)"
    "$keytool" -genkeypair -alias client-metadata -keyalg RSA -keysize 2048 -validity 3650 \
      -dname "CN=localhost" -ext "SAN=dns:localhost,ip:127.0.0.1" \
      -storetype PKCS12 -keystore certs/client-metadata-tls.p12 -storepass changeit -keypass changeit 2>/dev/null
    rm -f certs/client-metadata.crt certs/client-metadata-trust.p12
  fi
  if [ ! -f certs/client-metadata.crt ]; then
    # curl --cacert와 browser 확인에 쓰는 PEM 인증서다.
    "$keytool" -exportcert -alias client-metadata -keystore certs/client-metadata-tls.p12 -storepass changeit \
      -rfc -file certs/client-metadata.crt 2>/dev/null
  fi
  if [ ! -f certs/client-metadata-trust.p12 ]; then
    # Authorization Server가 문서 host를 믿을 때 쓰는 truststore다. 위 인증서 하나만 담는다.
    "$keytool" -importcert -noprompt -alias client-metadata -file certs/client-metadata.crt \
      -storetype PKCS12 -keystore certs/client-metadata-trust.p12 -storepass changeit 2>/dev/null
  fi
  if [ ! -f certs/client-signing.p12 ]; then
    echo "  [서명 key] certs/client-signing.p12 (RSA 2048)"
    "$keytool" -genkeypair -alias client-signing -keyalg RSA -keysize 2048 -validity 3650 \
      -dname "CN=cimd-shop-agent" -storetype PKCS12 -keystore certs/client-signing.p12 \
      -storepass changeit -keypass changeit 2>/dev/null
  fi
}

# $1 module, $2 포트, 나머지는 bootRun에 넘길 인자.
start() {
  local dir="$1" port="$2"
  shift 2
  if lsof -ti tcp:"$port" -sTCP:LISTEN > /dev/null 2>&1; then
    echo "  [건너뜀] $dir — 포트 $port 가 이미 사용 중입니다"
    return
  fi
  echo "  [기동] $dir (:$port)"
  # < /dev/null 과 disown 이 없으면 이 스크립트가 호출자를 붙잡는다.
  ( cd "$dir" && nohup ./gradlew bootRun -q "$@" > "../logs/$dir.log" 2>&1 < /dev/null & disown 2>/dev/null || true )
}

# $1 로그 이름(logs/<이름>.log), $2 주소, 나머지는 curl 인자. 응답이 오면(상태 코드와 상관없이) 준비된 것으로 본다.
wait_for() {
  local name="$1" url="$2"
  shift 2
  for _ in $(seq 1 90); do
    if [ "$(curl -s "$@" -o /dev/null -w '%{http_code}' "$url" || true)" != "000" ]; then
      echo "  [준비됨] $url"
      return 0
    fi
    sleep 1
  done
  echo "  [실패] $url 이 뜨지 않았습니다. logs/$name.log 마지막 20줄:"
  tail -20 "logs/$name.log" || true
  return 1
}

make_certs

echo "기동 순서: auth-server → shop-mcp-server → shop-agent (client type: $CLIENT_TYPE)"
start auth-server 9060
wait_for auth-server http://localhost:9060/.well-known/openid-configuration

start shop-mcp-server 8171
wait_for shop-mcp-server http://localhost:8171/mcp

# 이미 떠 있는 agent는 다시 띄우지 않으므로 이번에 고른 client type이 적용되지 않는다.
# 끝의 안내가 이 경우를 구분한다.
AGENT_ALREADY_RUNNING=0
if lsof -ti tcp:8170 -sTCP:LISTEN > /dev/null 2>&1; then
  AGENT_ALREADY_RUNNING=1
  echo "  [안내] shop-agent가 이미 떠 있습니다. client type을 바꾸려면 ./stop.sh 뒤에 다시 실행하세요."
fi
start shop-agent 8170 --args="--mcp.authorization.client-type=$CLIENT_TYPE"
wait_for shop-agent http://localhost:8170/
wait_for shop-agent https://localhost:8172/oauth/client.json --cacert certs/client-metadata.crt

if [ "$AGENT_ALREADY_RUNNING" = "1" ]; then
  echo
  echo "준비되었습니다. 다만 shop-agent는 이미 떠 있어서 client type을 바꾸지 않았습니다."
  echo "  지금 agent의 client type은 이번에 고른 값($CLIENT_TYPE)과 다를 수 있습니다."
  echo "  바꾸려면 ./stop.sh 뒤에 ./run.sh $CLIENT_TYPE 순서로 실행하세요."
else
  echo
  echo "준비되었습니다. agent의 client type은 $CLIENT_TYPE입니다."
fi

cat <<EOF

  브라우저에서 http://localhost:8170/ 을 엽니다.
  로그인: user / password (점원), user2 / password (손님)

  agent가 올린 client 문서:
    curl --cacert certs/client-metadata.crt https://localhost:8172/oauth/client.json
    curl --cacert certs/client-metadata.crt https://localhost:8172/oauth/public-client.json
    curl --cacert certs/client-metadata.crt https://localhost:8172/oauth/jwks.json

  다른 client type으로 다시 띄우기: ./stop.sh 뒤에 ./run.sh claude (또는 chatgpt)
  종료: ./stop.sh
EOF
