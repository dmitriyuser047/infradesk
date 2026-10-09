#!/usr/bin/env bash
# Contract checks against the production Caddy image and an isolated echo backend.
set -Eeuo pipefail
IMAGE="${1:-infradesk-frontend:local}"
NAME="infradesk-caddy-check-${RANDOM}-$$"
WORK="$(mktemp -d)"
cleanup() {
  docker rm -fv "${NAME}-proxy" "${NAME}-tls" "${NAME}-backend" >/dev/null 2>&1 || true
  docker network rm "${NAME}" >/dev/null 2>&1 || true
  rm -rf "${WORK}"
}
trap cleanup EXIT
docker network create "${NAME}" >/dev/null
gateway="$(docker network inspect --format '{{(index .IPAM.Config 0).Gateway}}' "${NAME}")"
docker run -d --name "${NAME}-backend" --network "${NAME}" --network-alias backend \
  node:24-alpine node -e 'require("http").createServer((req,res)=>{req.resume(); req.on("end",()=>{res.setHeader("Content-Type","application/json"); if(req.url==="/ready")res.statusCode=503; res.end(JSON.stringify({path:req.url,headers:req.headers}));});}).listen(8080,"0.0.0.0")' >/dev/null
docker run -d --name "${NAME}-proxy" --network "${NAME}" --network-alias proxy \
  -e "INFRADESK_TRUSTED_PROXY_CIDR=${gateway}/32" -p 127.0.0.1::80 "${IMAGE}" >/dev/null
base="http://$(docker port "${NAME}-proxy" 80/tcp)"
for _ in $(seq 1 30); do
  if curl --silent --fail "${base}/index.html" >/dev/null; then break; fi
  sleep 1
done
curl --silent --show-error --fail "${base}/organizations" >"${WORK}/shell"
grep -q '<div id="root"' "${WORK}/shell"
curl --silent --show-error --fail -D "${WORK}/headers" "${base}/organizations" >/dev/null
grep -qi '^Cache-Control: no-cache' "${WORK}/headers"
grep -qi '^X-Frame-Options: DENY' "${WORK}/headers"
asset="$(sed -n 's/.*src="\([^\"]*\/assets\/[^\"]*\.js\)".*/\1/p' "${WORK}/shell" | head -n 1)"
test -n "${asset}"
curl --silent --show-error --fail -D "${WORK}/headers" "${base}${asset}" >/dev/null
grep -qi '^Cache-Control: public, immutable, max-age=31536000' "${WORK}/headers"
test "$(curl --silent -o /dev/null -w '%{http_code}' "${base}/assets/missing.js")" = 404
test "$(curl --silent -o /dev/null -w '%{http_code}' "${base}/ready")" = 503
curl --silent --show-error --fail "${base}/api/echo" -H 'X-Forwarded-For: 203.0.113.7, 198.51.100.8' -H 'X-Forwarded-Proto: https' >"${WORK}/trusted"
grep -q '"x-real-ip":"198.51.100.8"' "${WORK}/trusted"
grep -q '"x-forwarded-proto":"https"' "${WORK}/trusted"
output="$(docker run --rm --network "${NAME}" curlimages/curl:8.12.1 --silent --show-error --fail \
  -H 'X-Forwarded-For: 203.0.113.7' -H 'X-Real-IP: 203.0.113.7' -H 'X-Forwarded-Proto: https' http://proxy/api/echo)"
if [[ "${output}" == *203.0.113.7* ]]; then echo 'Spoofed source reached backend'; exit 1; fi
[[ "${output}" == *'"x-forwarded-proto":"http"'* ]]
# A fresh container with one socket source rotates forged headers; edge throttle still applies.
docker run --rm --network "${NAME}" --entrypoint sh curlimages/curl:8.12.1 -c '
  for i in $(seq 1 12); do
    curl -sS -D /tmp/headers -o /tmp/body -H "X-Forwarded-For: 203.0.113.${i}" http://proxy/api/v1/auth/login
  done
  grep -q "429" /tmp/headers && grep -q "application/json" /tmp/headers && grep -q "LOGIN_RATE_LIMITED" /tmp/body' >/dev/null
head -c 1048577 /dev/zero >"${WORK}/body"
test "$(curl --silent -o /dev/null -w '%{http_code}' --data-binary @"${WORK}/body" "${base}/api/echo")" = 413
docker run --rm -e INFRADESK_SITE_ADDRESS=infradesk.example.test "${IMAGE}" \
  caddy validate --config /etc/caddy/Caddyfile >/dev/null
docker run -d --name "${NAME}-tls" --network "${NAME}" -e INFRADESK_SITE_ADDRESS=localhost \
  --mount type=volume,dst=/data -p 127.0.0.1::80 -p 127.0.0.1::443 "${IMAGE}" >/dev/null
http_port="$(docker port "${NAME}-tls" 80/tcp | cut -d: -f2)"
for _ in $(seq 1 30); do
  if docker exec "${NAME}-tls" cat /data/caddy/pki/authorities/local/root.crt >"${WORK}/root.crt" 2>/dev/null; then break; fi
  sleep 1
done
tls_ip="$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "${NAME}-tls")"
docker run --rm --user 0 --network "${NAME}" --volumes-from "${NAME}-tls:ro" --entrypoint sh curlimages/curl:8.12.1 -c \
  'curl --silent --show-error --fail --cacert /data/caddy/pki/authorities/local/root.crt --resolve "localhost:443:$1" https://localhost/organizations' sh "${tls_ip}" >/dev/null
test "$(curl --silent -o /dev/null -w '%{http_code}' -H 'Host: localhost' "http://127.0.0.1:${http_port}/")" = 308
echo 'Caddy proxy: routing, cache, security headers, source trust, login throttle, body limit and HTTPS configuration passed.'
