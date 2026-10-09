#!/usr/bin/env bash
# Unit tests of the installer's pure functions: platform detection, validation, version and
# subnet arithmetic, manifest parsing. No root, no Docker, nothing on the host changes.
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
. "${HERE}/../lib/common.sh"

FAILURES=0
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

ok() { if "$@"; then :; else printf 'FAIL (expected success): %s\n' "$*"; FAILURES=$((FAILURES + 1)); fi; }
no() { if "$@"; then printf 'FAIL (expected failure): %s\n' "$*"; FAILURES=$((FAILURES + 1)); fi; }
eq() { if [ "$1" != "$2" ]; then printf 'FAIL: %s: expected "%s", got "%s"\n' "$3" "$2" "$1"; FAILURES=$((FAILURES + 1)); fi; }

os_release() {
  printf 'PRETTY_NAME="%s"\nID=%s\nVERSION_ID="%s"\nVERSION_CODENAME=%s\n' "$1" "$2" "$3" "$4" > "${TMP}/os-release"
  printf '%s' "${TMP}/os-release"
}

# Supported platforms
ok platform_supported ubuntu 22.04 x86_64
ok platform_supported ubuntu 24.04 x86_64
ok platform_supported ubuntu 26.04 x86_64
ok platform_supported ubuntu 26.04 amd64
no platform_supported ubuntu 26.04 arm64
no platform_supported ubuntu 25.10 x86_64
no platform_supported ubuntu 28.04 x86_64
ok platform_supported debian 12 amd64
no platform_supported debian 11 x86_64
no platform_supported ubuntu 20.04 x86_64
no platform_supported ubuntu 24.04 aarch64
no platform_supported ubuntu 24.04 arm64
no platform_supported centos 9 x86_64
no platform_supported fedora 40 x86_64

# check_platform stops before any change, naming what it found
file="$(os_release "Debian GNU/Linux 11 (bullseye)" debian 11 bullseye)"
output="$( (check_platform "${file}" x86_64) 2>&1 || true)"
[[ "${output}" == *"Detected: Debian GNU/Linux 11 (bullseye) / x86_64"* ]] || { echo "FAIL: unsupported message: ${output}"; FAILURES=$((FAILURES + 1)); }
[[ "${output}" == *"No changes were made."* ]] || { echo "FAIL: unsupported message lacks 'No changes'"; FAILURES=$((FAILURES + 1)); }
file="$(os_release "Ubuntu 24.04 LTS" ubuntu 24.04 noble)"
if (check_platform "${file}" aarch64) >/dev/null 2>&1; then echo "FAIL: arm64 accepted"; FAILURES=$((FAILURES + 1)); fi
(check_platform "${file}" x86_64 && [ "${PLATFORM_CODENAME}" = noble ]) || { echo "FAIL: Ubuntu 24.04 rejected"; FAILURES=$((FAILURES + 1)); }
file="$(os_release "Ubuntu 26.04 LTS" ubuntu 26.04 resolute)"
(check_platform "${file}" x86_64 && [ "${PLATFORM_VERSION}" = 26.04 ] && [ "${PLATFORM_CODENAME}" = resolute ]) || { echo "FAIL: Ubuntu 26.04 rejected or Docker codename changed"; FAILURES=$((FAILURES + 1)); }
file="$(os_release "Debian GNU/Linux 12 (bookworm)" debian 12 bookworm)"
(check_platform "${file}" x86_64) || { echo "FAIL: Debian 12 rejected"; FAILURES=$((FAILURES + 1)); }

# Input validation
ok valid_domain infradesk.example.com
ok valid_domain infra-desk.example.com
no valid_domain https://example.com
no valid_domain 138.124.38.229
no valid_domain example..com
no valid_domain example.com.
no valid_domain '-example.com'
no valid_domain 'example.com { admin off }'
no valid_domain 'example.com$INFRADESK_DB_PASSWORD'
domain_url="$( (env_value() { printf 'infradesk.example.com'; }; base_url) )"
eq "${domain_url}" 'https://infradesk.example.com' 'automatic HTTPS URL'
compose_args="$( (env_value() { printf 'infradesk.example.com'; }; docker() { printf '%s\n' "$@"; }; compose_in /bundle up -d) )"
[[ "${compose_args}" == *'/bundle/compose.https.yml'* ]] || { echo 'FAIL: HTTPS overlay omitted'; FAILURES=$((FAILURES + 1)); }
compose_args="$( (env_value() { :; }; docker() { printf '%s\n' "$@"; }; compose_in /bundle up -d) )"
[[ "${compose_args}" != *compose.https.yml* ]] || { echo 'FAIL: HTTP install unexpectedly enabled HTTPS'; FAILURES=$((FAILURES + 1)); }
if (env_value() { printf 'example.com { }'; }; docker() { exit 0; }; compose_in /bundle up -d) >/dev/null 2>&1; then
  echo 'FAIL: invalid domain reached Compose'; FAILURES=$((FAILURES + 1))
fi
ok valid_port 1; ok valid_port 8080; ok valid_port 65535
no valid_port 0; no valid_port 65536; no valid_port 80a; no valid_port ""; no valid_port "8080 "
ok valid_ipv4 127.0.0.1; ok valid_ipv4 0.0.0.0; ok valid_ipv4 192.168.1.20
no valid_ipv4 256.1.1.1; no valid_ipv4 localhost; no valid_ipv4 "1.2.3"; no valid_ipv4 '1.2.3.4;rm'
ok valid_email admin@example.com
no valid_email admin; no valid_email "a b@example.com"; no valid_email '@example.com'
ok valid_release_version v0.1.0; ok valid_release_version v10.20.30
no valid_release_version v1; no valid_release_version release-final; no valid_release_version latest2
no valid_release_version test123; no valid_release_version v01.2.3; no valid_release_version 0.1.0
ok valid_password "twelve-chars"; no valid_password "short"; no valid_password "$(printf 'x%.0s' $(seq 1 129))"
no valid_password "$(printf 'line\nbreak-password')"
ok valid_display_text "Ops Team"; no valid_display_text ""; no valid_display_text "$(printf 'a\tb')"

# Versions
ok version_ge 24.0.7 24.0.0; ok version_ge 2.29.1 2.20.0; ok version_ge v0.1.1 v0.1.0; ok version_ge v0.2.0 v0.1.9
no version_ge 23.0.6 24.0.0; no version_ge 2.19.9 2.20.0; no version_ge v0.1.0 v0.1.1; ok version_ge v0.1.0 v0.1.0
ok version_ge 27.3.1-ce 24.0.0

# Subnets
ok cidr_overlap 172.28.0.0/24 172.28.0.0/24
ok cidr_overlap 172.28.0.0/24 172.16.0.0/12
ok cidr_overlap 10.0.0.0/8 10.213.0.0/24
no cidr_overlap 172.28.0.0/24 172.29.0.0/24
no cidr_overlap 172.28.0.0/24 192.168.0.0/16
eq "$(subnet_gateway_cidr 172.29.0.0/24)" "172.29.0.1/32" "gateway of a /24"

# Manifest
cat > "${TMP}/release-manifest.json" <<'EOF'
{
  "version": "0.1.0",
  "gitSha": "0123456789abcdef0123456789abcdef01234567",
  "backendImage": "ghcr.io/o/infradesk-backend@sha256:aaaa",
  "schemaVersion": 37,
  "installerSchemaVersion": 1
}
EOF
eq "$(manifest_value "${TMP}/release-manifest.json" version)" "0.1.0" "manifest version"
eq "$(manifest_value "${TMP}/release-manifest.json" gitSha)" "0123456789abcdef0123456789abcdef01234567" "manifest sha"
eq "$(manifest_value "${TMP}/release-manifest.json" backendImage)" "ghcr.io/o/infradesk-backend@sha256:aaaa" "manifest image"
eq "$(manifest_value "${TMP}/release-manifest.json" schemaVersion)" "37" "manifest schema"

# Environment values are read, never executed
printf 'INFRADESK_A=plain\nINFRADESK_B=$(touch %s/executed)\nINFRADESK_C=with=equals==\n' "${TMP}" > "${TMP}/env"
eq "$(env_value INFRADESK_A "${TMP}/env")" "plain" "env value"
eq "$(env_value INFRADESK_C "${TMP}/env")" "with=equals==" "env value with equals"
env_value INFRADESK_B "${TMP}/env" >/dev/null
[ ! -e "${TMP}/executed" ] || { echo "FAIL: an env value was executed"; FAILURES=$((FAILURES + 1)); }

# Port listeners: parsing of ss output for a given bind address
ss() { printf '%s\n' 'LISTEN 0 511 127.0.0.1:8080 0.0.0.0:* users:(("nginx",pid=1))' 'LISTEN 0 511 [::]:9090 [::]:*'; }
have_command() { return 0; }
[ -n "$(port_listeners 127.0.0.1 8080)" ] || { echo "FAIL: loopback listener not detected"; FAILURES=$((FAILURES + 1)); }
[ -n "$(port_listeners 0.0.0.0 8080)" ] || { echo "FAIL: listener not detected for 0.0.0.0"; FAILURES=$((FAILURES + 1)); }
[ -n "$(port_listeners 127.0.0.1 9090)" ] || { echo "FAIL: wildcard IPv6 listener not detected"; FAILURES=$((FAILURES + 1)); }
[ -z "$(port_listeners 127.0.0.1 8081)" ] || { echo "FAIL: free port reported busy"; FAILURES=$((FAILURES + 1)); }
[ -z "$(port_listeners 10.0.0.5 8080)" ] || { echo "FAIL: a loopback listener blocks another address"; FAILURES=$((FAILURES + 1)); }
unset -f ss have_command

# Docker absent: without --install-docker a non-interactive run stops before any change
have_command() { [ "$1" != docker ]; }
install_docker() { echo INSTALLED > "${TMP}/docker-installed"; }
output="$( (check_docker no no) 2>&1 || true)"
[[ "${output}" == *"Docker is not installed."* && "${output}" == *"--install-docker"* ]] ||
  { echo "FAIL: docker-absent message: ${output}"; FAILURES=$((FAILURES + 1)); }
[ ! -e "${TMP}/docker-installed" ] || { echo "FAIL: Docker was installed without being asked"; FAILURES=$((FAILURES + 1)); }
unset -f have_command install_docker

if [ "${FAILURES}" -gt 0 ]; then printf '\n%s unit test(s) failed.\n' "${FAILURES}"; exit 1; fi
printf 'All installer unit tests passed.\n'
