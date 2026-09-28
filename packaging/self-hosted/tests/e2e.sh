#!/usr/bin/env bash
# End-to-end test of the self-hosted release bundles, run as root on a host with Docker, exactly as
# a user would: verify, extract, install, operate, back up, restore, update, uninstall.
#
#   sudo packaging/self-hosted/tests/e2e.sh DIST_A DIST_B GIT_SHA
#
# DIST_A and DIST_B each hold one built bundle (built by build-bundle.sh) and its SHA256SUMS; B is
# the newer release. Secrets are never printed: the admin password is test-only and generated here.
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DIST_A="$(cd "$1" && pwd)"
DIST_B="$(cd "$2" && pwd)"
GIT_SHA="$3"
WORK="$(mktemp -d /tmp/infradesk-e2e.XXXXXX)"
URL="http://127.0.0.1:8080"
ADMIN_EMAIL="admin@example.test"
PASSWORD_FILE="${WORK}/admin-password"
SSH_PASSWORD="stage20-ci-only-password"

step() { printf '\n==== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_eq() { [ "$1" = "$2" ] || fail "$3: expected '$2', got '$1'"; }
psql_value() {
  docker exec infradesk-postgres-1 psql -tA --username infradesk --dbname infradesk --command "$1" | tr -d '[:space:]'
}
env_hash() { sha256sum /etc/infradesk/infradesk.env | awk '{ print $1 }'; }
bundle_of() { find "$1" -maxdepth 1 -name 'infradesk-v*-linux-amd64.tar.gz' | head -n 1; }
version_of() { basename "$(bundle_of "$1")" | sed -E 's/^infradesk-(v[0-9.]+)-linux-amd64\.tar\.gz$/\1/'; }

VERSION_A="$(version_of "${DIST_A}")"
VERSION_B="$(version_of "${DIST_B}")"

# Signs in and prints the session cookie, taken from the header so a Secure cookie works over the
# loopback address too.
login_cookie() {
  curl --silent --show-error --fail --dump-header - --output /dev/null \
    --header 'Content-Type: application/json' \
    --data "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"$(cat "${PASSWORD_FILE}")\"}" \
    "${URL}/api/v1/auth/login" | tr -d '\r' | sed -n 's/^[Ss]et-[Cc]ookie: \(infradesk_session=[^;]*\).*/\1/p'
}

api() {
  local method="$1" path="$2" body="${3:-}"
  curl --silent --show-error --fail --request "${method}" --header "Cookie: ${COOKIE}" \
    --header 'Content-Type: application/json' ${body:+--data "${body}"} "${URL}${path}"
}

terminal_check() {
  INFRADESK_BASE_URL="${URL}" \
  INFRADESK_TERMINAL_TEST_ORGANIZATION_ID="${ORGANIZATION_ID}" \
  INFRADESK_TERMINAL_TEST_CONNECTION_ID="${CONNECTION_ID}" \
  INFRADESK_TERMINAL_TEST_EMAIL="${ADMIN_EMAIL}" \
  INFRADESK_TERMINAL_TEST_PASSWORD="$(cat "${PASSWORD_FILE}")" \
    node "${REPO_ROOT}/scripts/check-terminal-websocket.mjs"
}

project_names() { psql_value "select string_agg(code, ',' order by code) from project"; }

cleanup() {
  docker rm --force infradesk-e2e-sshd >/dev/null 2>&1 || true
  if [ -n "${LISTENER:-}" ]; then kill "${LISTENER}" 2>/dev/null || true; fi
  rm -rf "${WORK}"
}
trap cleanup EXIT

step "Verify and extract both bundles like a user"
(cd "${DIST_A}" && sha256sum -c SHA256SUMS --ignore-missing)
(cd "${DIST_B}" && sha256sum -c SHA256SUMS --ignore-missing)
tar -xzf "$(bundle_of "${DIST_A}")" -C "${WORK}"
mkdir "${WORK}/b"
tar -xzf "$(bundle_of "${DIST_B}")" -C "${WORK}/b"
BUNDLE_A="${WORK}/infradesk-${VERSION_A}"
BUNDLE_B="${WORK}/b/infradesk-${VERSION_B}"
for forbidden in node_modules target src .git; do
  [ ! -e "${BUNDLE_A}/${forbidden}" ] || fail "bundle contains ${forbidden}"
done
grep -Eq 'image: "[^"]+@sha256:[0-9a-f]{64}"' "${BUNDLE_A}/compose.yml" || fail "compose images are not pinned by digest"
if grep -Eq 'image: "[^"@]+:(latest|master|edge)"' "${BUNDLE_A}/compose.yml"; then fail "compose uses a mutable tag"; fi
openssl rand -base64 24 | tr -d '\n' > "${PASSWORD_FILE}"
chmod 600 "${PASSWORD_FILE}"

step "Port collision: nothing is created and the listener is untouched"
python3 -m http.server 8080 --bind 127.0.0.1 >/dev/null 2>&1 &
LISTENER=$!
for _ in $(seq 1 50); do ss -Hltn 'sport = :8080' | grep -q . && break; sleep 0.1; done
if (cd /tmp && "${BUNDLE_A}/install.sh" --non-interactive --admin-email "${ADMIN_EMAIL}" \
  --admin-password-file "${PASSWORD_FILE}") > "${WORK}/collision.log" 2>&1; then
  fail "install succeeded on an occupied port"
fi
grep -q 'InfraDesk cannot bind 127.0.0.1:8080' "${WORK}/collision.log" || fail "no port message: $(cat "${WORK}/collision.log")"
grep -q 'No containers were started' "${WORK}/collision.log" || fail "collision message incomplete"
[ ! -e /etc/infradesk ] && [ ! -e /opt/infradesk ] || fail "the failed install created files"
[ -z "$(docker ps -aq --filter label=com.docker.compose.project=infradesk)" ] || fail "containers exist after the failed install"
kill -0 "${LISTENER}" || fail "the existing listener was killed"
kill "${LISTENER}"; wait "${LISTENER}" 2>/dev/null || true; LISTENER=""

step "Fresh install of ${VERSION_A}"
(cd /tmp && "${BUNDLE_A}/install.sh" --non-interactive --admin-email "${ADMIN_EMAIL}" --admin-name "CI Admin" \
  --organization-name "E2E Organization" --admin-password-file "${PASSWORD_FILE}") | tee "${WORK}/install.log"
grep -q 'InfraDesk installed successfully' "${WORK}/install.log" || fail "no success message"
if grep -Fq "$(cat "${PASSWORD_FILE}")" "${WORK}/install.log"; then fail "the admin password was printed"; fi
for key in INFRADESK_DB_PASSWORD INFRADESK_SECRET_MASTER_KEY_BASE64; do
  value="$(sed -n "s/^${key}=//p" /etc/infradesk/infradesk.env)"
  [ "${#value}" -ge 40 ] || fail "${key} was not generated"
  if grep -Fq "${value}" "${WORK}/install.log"; then fail "${key} was printed"; fi
done

step "Secret file permissions and installer state"
assert_eq "$(stat -c '%U:%G %a' /etc/infradesk/infradesk.env)" "root:root 600" "env file owner and mode"
assert_eq "$(stat -c '%a' /etc/infradesk)" "700" "config directory mode"
[ ! -e /etc/infradesk/bootstrap.env ] || fail "the bootstrap file outlived the installation"
grep -q '^STATUS=installed$' /opt/infradesk/install-state || fail "state is not installed"
grep -q "^VERSION=${VERSION_A}$" /opt/infradesk/install-state || fail "state has no version"
if grep -Eiq 'password|secret|master_key' /opt/infradesk/install-state; then fail "install-state holds a secret"; fi
bootstrap_env="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' infradesk-backend-1 | sed -n 's/^INFRADESK_BOOTSTRAP_PASSWORD=//p')"
assert_eq "${bootstrap_env}" "" "backend still carries the bootstrap password"

step "Network exposure: only the web entrypoint is published"
assert_eq "$(docker port infradesk-proxy-1 | tr -d '\n')" "80/tcp -> 127.0.0.1:8080" "proxy publication"
assert_eq "$(docker port infradesk-backend-1)" "" "backend publication"
assert_eq "$(docker port infradesk-postgres-1)" "" "postgres publication"
if ss -Hltn | awk '{ print $4 }' | grep -Eq ':5432$'; then fail "PostgreSQL listens on the host"; fi
for container in infradesk-postgres-1 infradesk-backend-1 infradesk-proxy-1; do
  assert_eq "$(docker inspect --format '{{.HostConfig.Privileged}} {{.HostConfig.NetworkMode}}' "${container}")" \
    "false infradesk_internal" "${container} isolation"
  if docker inspect --format '{{range .Mounts}}{{.Source}} {{end}}' "${container}" | grep -q docker.sock; then
    fail "${container} mounts the Docker socket"
  fi
done
assert_eq "$(docker inspect --format '{{.Config.User}}' infradesk-backend-1)" "infradesk" "backend user"

step "Build identity through the public entrypoint"
health="$(curl -fsS "${URL}/health")"
assert_eq "$(printf '%s' "${health}" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')" "${VERSION_A#v}" "running version"
assert_eq "$(printf '%s' "${health}" | sed -n 's/.*"gitSha":"\([^"]*\)".*/\1/p')" "${GIT_SHA}" "running commit"
assert_eq "$(psql_value "select max(version::int) from flyway_schema_history where success")" \
  "$(sed -n 's/.*"schemaVersion": \([0-9]*\).*/\1/p' "${BUNDLE_A}/release-manifest.json")" "schema is the latest migration"

step "CLI from an arbitrary directory"
cd /tmp
infradesk status | tee "${WORK}/status.log"
grep -q "InfraDesk ${VERSION_A}" "${WORK}/status.log" || fail "status has no version"
infradesk version | tee "${WORK}/version.log"
grep -q "v${VERSION_A#v}" "${WORK}/version.log" && ! grep -q 'do not match' "${WORK}/version.log" || fail "version mismatch"
infradesk doctor | tee "${WORK}/doctor.log"
for key in INFRADESK_DB_PASSWORD INFRADESK_SECRET_MASTER_KEY_BASE64; do
  if grep -Fq "$(sed -n "s/^${key}=//p" /etc/infradesk/infradesk.env)" "${WORK}/doctor.log"; then fail "doctor printed ${key}"; fi
done
infradesk logs backend > "${WORK}/logs.log"
grep -q 'application.started' "${WORK}/logs.log" || fail "backend logs are not available"
infradesk logs frontend >/dev/null
infradesk logs postgres >/dev/null

step "Representative data: a project and an SSH connection with an encrypted credential"
COOKIE="$(login_cookie)"
[ -n "${COOKIE}" ] || fail "the bootstrap administrator cannot sign in"
ORGANIZATION_ID="$(sed -n 's/^ORGANIZATION_ID=//p' /opt/infradesk/install-state)"
api POST "/api/v1/organizations/${ORGANIZATION_ID}/projects" '{"code":"before-backup","name":"Before backup","description":null}' >/dev/null
docker build --quiet --file "${REPO_ROOT}/deploy/ci-terminal-sshd.Dockerfile" --tag infradesk-e2e-sshd "${REPO_ROOT}" >/dev/null
docker run --detach --name infradesk-e2e-sshd --network infradesk_internal --network-alias terminal-sshd infradesk-e2e-sshd >/dev/null
fingerprint=""
for _ in $(seq 1 30); do
  fingerprint="$(api POST "/api/v1/organizations/${ORGANIZATION_ID}/connections/ssh/host-key" \
    '{"host":"terminal-sshd","port":22,"username":"ci"}' 2>/dev/null | sed -n 's/.*"hostKeyFingerprint":"\([^"]*\)".*/\1/p')" || true
  [ -n "${fingerprint}" ] && break
  sleep 1
done
[ -n "${fingerprint}" ] || fail "the SSH host key could not be read"
CONNECTION_ID="$(api POST "/api/v1/organizations/${ORGANIZATION_ID}/connections" "{\"connectorType\":\"SSH\",\"code\":\"e2e-ssh\",
  \"name\":\"E2E SSH\",\"scope\":{\"type\":\"ORGANIZATION\",\"projectId\":null,\"environmentId\":null},
  \"ssh\":{\"host\":\"terminal-sshd\",\"port\":22,\"username\":\"ci\",\"authenticationType\":\"PASSWORD\",\"hostKeyFingerprint\":\"${fingerprint}\"},
  \"credentials\":{\"type\":\"PASSWORD\",\"password\":\"${SSH_PASSWORD}\",\"privateKey\":null,\"passphrase\":null},
  \"schedule\":{\"enabled\":false,\"intervalSeconds\":300}}" | grep -o '"id":"[^"]*"' | head -n 1 | cut -d'"' -f4)"
[ -n "${CONNECTION_ID}" ] || fail "the SSH connection was not created"
if psql_value "select config::text || coalesce(secret_ref, '') from connection" | grep -Fq "${SSH_PASSWORD}"; then
  fail "the SSH password is stored in plain text"
fi
terminal_check

step "Running the installer again changes nothing"
before_env="$(env_hash)"
before_db="$(stat -c '%i' /var/lib/infradesk/postgres/PG_VERSION)"
(cd /tmp && "${BUNDLE_A}/install.sh" --non-interactive) | tee "${WORK}/again.log"
grep -q 'InfraDesk is already installed' "${WORK}/again.log" || fail "no already-installed notice"
(cd /tmp && "${BUNDLE_A}/install.sh" --non-interactive --reconcile) >/dev/null
assert_eq "$(env_hash)" "${before_env}" "configuration after re-running the installer"
assert_eq "$(stat -c '%i' /var/lib/infradesk/postgres/PG_VERSION)" "${before_db}" "database after re-running the installer"
assert_eq "$(psql_value 'select count(*) from user_account')" "1" "administrators after re-running the installer"
assert_eq "$(project_names)" "before-backup" "projects after re-running the installer"

step "Restart and stop/start keep the data"
infradesk restart
infradesk stop
infradesk start
assert_eq "$(project_names)" "before-backup" "projects after restart"
COOKIE="$(login_cookie)"; [ -n "${COOKIE}" ] || fail "sign-in after restart"

step "Backup, change, restore"
backup="$(infradesk backup | sed -n 's/^Backup written: //p')"
[ -s "${backup}" ] && [ -s "${backup}.meta" ] || fail "backup files missing"
assert_eq "$(stat -c '%a' "${backup}")" "600" "backup mode"
grep -q "^VERSION=${VERSION_A}$" "${backup}.meta" || fail "backup metadata has no version"
api POST "/api/v1/organizations/${ORGANIZATION_ID}/projects" '{"code":"after-backup","name":"After backup","description":null}' >/dev/null
assert_eq "$(project_names)" "after-backup,before-backup" "projects before restore"

step "A backup from a newer schema is refused before anything changes"
psql_value "insert into flyway_schema_history (installed_rank, version, description, type, script, installed_by, execution_time, success)
  values ((select max(installed_rank) + 1 from flyway_schema_history), '999', 'future', 'SQL', 'V999__future.sql', 'e2e', 0, true)" >/dev/null
future="$(infradesk backup | sed -n 's/^Backup written: //p')"
psql_value "delete from flyway_schema_history where version = '999'" >/dev/null
if infradesk restore "${future}" --yes > "${WORK}/future.log" 2>&1; then fail "a newer-schema backup was restored"; fi
grep -q 'Backup requires schema V999' "${WORK}/future.log" || fail "no schema guard message: $(cat "${WORK}/future.log")"
assert_eq "$(project_names)" "after-backup,before-backup" "database after the refused restore"
if infradesk restore /etc/hostname --yes >/dev/null 2>&1; then fail "a non-backup file was restored"; fi

infradesk restore "${backup}" --yes | tee "${WORK}/restore.log"
assert_eq "$(project_names)" "before-backup" "projects after restore"
ls /var/backups/infradesk/pre-restore-*.dump >/dev/null || fail "no emergency backup before restore"
# The same encryption key: the restored credential still opens an SSH session.
terminal_check

step "A tampered bundle is rejected"
cp "$(bundle_of "${DIST_B}")" "${WORK}/tampered.tar.gz"
printf 'x' >> "${WORK}/tampered.tar.gz"
cp "${WORK}/tampered.tar.gz" "${WORK}/$(basename "$(bundle_of "${DIST_B}")")"
if infradesk update --from-file "${WORK}/$(basename "$(bundle_of "${DIST_B}")")" --sha256sums "${DIST_B}/SHA256SUMS" \
  > "${WORK}/tampered.log" 2>&1; then fail "a tampered bundle was accepted"; fi
grep -q 'does not match SHA256SUMS' "${WORK}/tampered.log" || fail "no checksum message"

step "A failing pre-update backup stops the update before anything changes"
compose_before="$(sha256sum /opt/infradesk/compose.yml | awk '{ print $1 }')"
docker stop infradesk-postgres-1 >/dev/null
if infradesk update --from-file "$(bundle_of "${DIST_B}")" --sha256sums "${DIST_B}/SHA256SUMS" > "${WORK}/nobackup.log" 2>&1; then
  fail "the update continued without a backup"
fi
grep -q 'pre-update backup failed' "${WORK}/nobackup.log" || fail "no backup failure message: $(cat "${WORK}/nobackup.log")"
assert_eq "$(sed -n 's/^VERSION=//p' /opt/infradesk/install-state)" "${VERSION_A}" "version after the refused update"
assert_eq "$(sha256sum /opt/infradesk/compose.yml | awk '{ print $1 }')" "${compose_before}" "compose file after the refused update"
infradesk start

step "Update ${VERSION_A} -> ${VERSION_B}"
infradesk update --from-file "$(bundle_of "${DIST_B}")" --sha256sums "${DIST_B}/SHA256SUMS" | tee "${WORK}/update.log"
grep -q "InfraDesk was updated to ${VERSION_B}" "${WORK}/update.log" || fail "no update message"
ls /var/backups/infradesk/pre-update-"${VERSION_B}"-*.dump >/dev/null || fail "no pre-update backup"
assert_eq "$(sed -n 's/^VERSION=//p' /opt/infradesk/install-state)" "${VERSION_B}" "version after update"
assert_eq "$(curl -fsS "${URL}/health" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')" "${VERSION_B#v}" "running version after update"
assert_eq "$(env_hash)" "${before_env}" "configuration after update"
assert_eq "$(project_names)" "before-backup" "projects after update"
terminal_check
infradesk update --from-file "$(bundle_of "${DIST_B}")" --sha256sums "${DIST_B}/SHA256SUMS" | grep -q 'already at' ||
  fail "a repeated update did not say it was up to date"
if infradesk update --from-file "$(bundle_of "${DIST_A}")" --sha256sums "${DIST_A}/SHA256SUMS" >/dev/null 2>&1; then
  fail "a downgrade was accepted"
fi

step "Uninstall keeps the data; installing again reuses it"
docker rm --force infradesk-e2e-sshd >/dev/null
infradesk uninstall --yes
[ ! -e /opt/infradesk ] && [ ! -e /usr/local/bin/infradesk ] || fail "application files remain"
[ -z "$(docker ps -aq --filter label=com.docker.compose.project=infradesk)" ] || fail "containers remain"
[ -f /etc/infradesk/infradesk.env ] && [ -f /var/lib/infradesk/postgres/PG_VERSION ] || fail "data was removed"
ls /var/backups/infradesk/*.dump >/dev/null || fail "backups were removed"
(cd /tmp && "${BUNDLE_B}/install.sh" --non-interactive) | tee "${WORK}/reinstall.log"
grep -q 'Reusing the existing InfraDesk database' "${WORK}/reinstall.log" || fail "the existing database was not reused"
assert_eq "$(env_hash)" "${before_env}" "configuration after reinstall"
assert_eq "$(project_names)" "before-backup" "projects after reinstall"
COOKIE="$(login_cookie)"; [ -n "${COOKIE}" ] || fail "sign-in after reinstall"

step "Purge is guarded and removes only InfraDesk data"
if /opt/infradesk/uninstall.sh --purge-data --yes >/dev/null 2>&1; then fail "purge ran without the hostname confirmation"; fi
[ -f /var/lib/infradesk/postgres/PG_VERSION ] || fail "the refused purge removed data"
infradesk uninstall --purge-data --yes --confirm-hostname "$(hostname)"
[ ! -e /var/lib/infradesk/postgres ] && [ ! -e /etc/infradesk/infradesk.env ] || fail "purge left data"
ls /var/backups/infradesk/*.dump >/dev/null || fail "purge removed backups"

printf '\nSelf-hosted end-to-end test passed.\n'
