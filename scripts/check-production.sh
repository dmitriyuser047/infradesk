#!/usr/bin/env bash
# Smoke-checks a running deployment: liveness, database readiness, the application shell and a
# deep link. Exits non-zero the moment something a deployment needs is not answering.
set -euo pipefail

BASE_URL="${1:-${INFRADESK_BASE_URL:-http://127.0.0.1:8081}}"

check() {
  local path="$1" expected="$2" description="$3"
  local status
  status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --max-time 10 "${BASE_URL}${path}")"
  if [ "${status}" != "${expected}" ]; then
    echo "FAIL ${description}: ${path} returned ${status}, expected ${expected}" >&2
    return 1
  fi
  echo "ok   ${description}: ${path} -> ${status}"
}

check "/health" 200 "backend liveness"
check "/ready" 200 "database readiness"
check "/" 200 "application shell"
# React Router owns this path; the proxy has to answer it with the shell rather than a 404.
check "/organizations" 200 "client-side deep link"

# Vite emits content-addressed files. Verify that the shell references one and Caddy serves it,
# rather than declaring the frontend healthy from index.html alone.
asset_path="$(curl --silent --show-error --fail --max-time 10 "${BASE_URL}/" \
  | sed -n 's/.*src="\([^\"]*\/assets\/[^\"]*\.js\)".*/\1/p' | head -n 1)"
[ -n "${asset_path}" ] || { echo "FAIL application shell references no JavaScript asset" >&2; exit 1; }
check "${asset_path}" 200 "hashed frontend asset"

echo "deployment looks healthy at ${BASE_URL}"
