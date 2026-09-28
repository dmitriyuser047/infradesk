#!/usr/bin/env bash
# Builds the self-hosted release bundle from this source tree: renders compose.yml with exact image
# references, writes VERSION and release-manifest.json, and produces the archive plus SHA256SUMS.
# Used by the release workflow and by CI, so the release path is tested before any release exists.
#
#   packaging/self-hosted/build-bundle.sh --version v0.1.0 --git-sha <40 hex> \
#     --backend-image ghcr.io/o/infradesk-backend@sha256:... \
#     --frontend-image ghcr.io/o/infradesk-frontend@sha256:... \
#     --postgres-image postgres@sha256:... --output dist
set -Eeuo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SOURCE_DIR}/../.." && pwd)"
# Global, so the exit trap still sees it after main returns.
STAGING=""
trap 'if [ -n "${STAGING}" ]; then rm -rf "${STAGING}"; fi' EXIT

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

main() {
  local version="" sha="" backend="" frontend="" postgres="" output="" schema name bundle
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --version) version="${2:-}"; shift 2 ;;
      --git-sha) sha="${2:-}"; shift 2 ;;
      --backend-image) backend="${2:-}"; shift 2 ;;
      --frontend-image) frontend="${2:-}"; shift 2 ;;
      --postgres-image) postgres="${2:-}"; shift 2 ;;
      --output) output="${2:-}"; shift 2 ;;
      *) die "unknown argument: $1" ;;
    esac
  done
  [[ "${version}" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || die "--version must be vMAJOR.MINOR.PATCH"
  [[ "${sha}" =~ ^[0-9a-f]{40}$ ]] || die "--git-sha must be a full 40-character commit SHA"
  for image in "${backend}" "${frontend}" "${postgres}"; do
    # Only content-addressed references: a release never points at a tag that can move.
    [[ "${image}" =~ ^[a-z0-9./:_-]+@sha256:[0-9a-f]{64}$ ]] || die "image references must be pinned by digest: '${image}'"
  done
  [ -n "${output}" ] || die "--output is required"

  schema="$(find "${REPO_ROOT}/src/main/resources/db/migration" -name 'V*__*.sql' -printf '%f\n' |
    sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | tail -n 1)"
  [ -n "${schema}" ] || die "no Flyway migrations found"

  name="infradesk-${version}"
  STAGING="$(mktemp -d)"
  bundle="${STAGING}/${name}"
  mkdir -p "${bundle}/lib"

  for file in install.sh update.sh backup.sh restore.sh uninstall.sh infradesk; do
    install -m 0755 "${SOURCE_DIR}/${file}" "${bundle}/${file}"
  done
  install -m 0644 "${SOURCE_DIR}/lib/common.sh" "${bundle}/lib/common.sh"
  install -m 0644 "${SOURCE_DIR}/env.example" "${bundle}/.env.example"
  install -m 0644 "${SOURCE_DIR}/README.md" "${bundle}/README.md"
  sed -e "s|@@BACKEND_IMAGE@@|${backend}|" -e "s|@@FRONTEND_IMAGE@@|${frontend}|" -e "s|@@POSTGRES_IMAGE@@|${postgres}|" \
    "${SOURCE_DIR}/compose.yml" > "${bundle}/compose.yml"
  grep -q '@@' "${bundle}/compose.yml" && die "compose.yml still has unrendered placeholders"
  printf '%s\n' "${version}" > "${bundle}/VERSION"
  # One key per line: the installer reads it without jq.
  cat > "${bundle}/release-manifest.json" <<EOF
{
  "version": "${version#v}",
  "gitSha": "${sha}",
  "backendImage": "${backend}",
  "frontendImage": "${frontend}",
  "postgresImage": "${postgres}",
  "schemaVersion": ${schema},
  "installerSchemaVersion": 1
}
EOF

  mkdir -p "${output}"
  output="$(cd "${output}" && pwd)"
  # Reproducible metadata: fixed owner and ordering, so the archive depends only on its content.
  tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='@0' -C "${STAGING}" -czf "${output}/${name}-linux-amd64.tar.gz" "${name}"
  (cd "${output}" && sha256sum "${name}-linux-amd64.tar.gz" > "${name}-linux-amd64.tar.gz.sha256" &&
    sha256sum "${name}-linux-amd64.tar.gz" > SHA256SUMS)
  printf 'Built %s/%s-linux-amd64.tar.gz (schema V%s)\n' "${output}" "${name}" "${schema}"
}

main "$@"
