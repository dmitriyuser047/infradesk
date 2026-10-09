#!/usr/bin/env bash
# Runs the real installer of a built bundle inside clean OS containers that have no Docker:
# a supported system must stop at the Docker check, an unsupported one at the platform check, and
# neither may create a file or install a package.
#
#   packaging/self-hosted/tests/preflight.sh DIST
set -Eeuo pipefail

DIST="$(cd "$1" && pwd)"
ARCHIVE="$(find "${DIST}" -maxdepth 1 -name 'infradesk-v*-linux-amd64.tar.gz' | head -n 1)"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
tar -xzf "${ARCHIVE}" -C "${WORK}"
BUNDLE="$(find "${WORK}" -mindepth 1 -maxdepth 1 -type d | head -n 1)"

run_in() {
  docker run --rm --volume "${BUNDLE}:/bundle:ro" "$1" bash -c '
    /bundle/install.sh --non-interactive --admin-email admin@example.test >/tmp/out 2>&1
    status=$?
    cat /tmp/out
    echo "exit=${status}"
    for path in /etc/infradesk /opt/infradesk /var/lib/infradesk /usr/local/bin/infradesk /etc/apt/sources.list.d/docker.list; do
      if [ -e "${path}" ]; then echo "created=${path}"; fi
    done'
}

failures=0
expect() {
  local image="$1" expected="$2" output
  output="$(run_in "${image}")"
  if [[ "${output}" != *"${expected}"* ]] || [[ "${output}" != *"exit=1"* ]] || [[ "${output}" == *"created="* ]] ||
    [[ "${output}" != *"No changes were made."* ]]; then
    printf 'FAIL %s:\n%s\n' "${image}" "${output}"
    failures=$((failures + 1))
  else
    printf 'ok   %s: %s\n' "${image}" "${expected}"
  fi
}

expect ubuntu:22.04 "Docker is not installed."
expect ubuntu:24.04 "Docker is not installed."
expect ubuntu:26.04 "Docker is not installed."
expect debian:12 "Docker is not installed."
expect debian:11 "This platform is not supported."
expect ubuntu:20.04 "This platform is not supported."

[ "${failures}" -eq 0 ] || exit 1
printf 'Installer preflight behaves on every platform.\n'
