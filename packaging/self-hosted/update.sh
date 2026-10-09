#!/usr/bin/env bash
# Updates InfraDesk to another release. Never a git pull: a release bundle is downloaded from the
# official repository (or given as a local file), its checksum verified, and the new bundle's own
# update logic applied:
#
#   sudo infradesk update                   the latest stable release
#   sudo infradesk update v0.1.1            an exact release
#   sudo infradesk update --from-file infradesk-v0.1.1-linux-amd64.tar.gz --sha256sums SHA256SUMS
#
# Apply: back up the database (the update stops if that fails), pull the new images, replace the
# runtime files atomically, `docker compose up -d`, wait for readiness, smoke-check, record the
# version. The configuration file and its secrets are kept. There is no automatic downgrade: once
# a new release has migrated the database, going back means restoring the pre-update backup.
set -Eeuo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "${SELF_DIR}/lib/common.sh"

WORK_DIR=""
cleanup() { if [ -n "${WORK_DIR}" ]; then rm -rf "${WORK_DIR}"; fi; }
trap cleanup EXIT

usage() {
  cat <<'EOF'
Usage: sudo infradesk update [VERSION]
       sudo infradesk update --from-file ARCHIVE --sha256sums SHA256SUMS

  VERSION   A release such as v0.1.1; without it the latest stable release is used.
EOF
}

# --- resolving and verifying a bundle ----------------------------------------------------------

latest_release() {
  local tag
  tag="$(curl -fsSL --max-time 30 "https://api.github.com/repos/${INFRADESK_RELEASE_REPO}/releases/latest" 2>/dev/null |
    sed -n 's/^[[:space:]]*"tag_name":[[:space:]]*"\([^"]*\)".*/\1/p' | awk 'NR == 1')" || true
  [ -n "${tag}" ] || die "The latest release could not be determined from github.com/${INFRADESK_RELEASE_REPO}." \
    "Name the version explicitly (infradesk update v0.1.1), or download the bundle and SHA256SUMS" \
    "yourself and use: infradesk update --from-file ARCHIVE --sha256sums SHA256SUMS"
  printf '%s' "${tag}"
}

download_release() {
  local tag="$1" base archive
  archive="infradesk-${tag}-linux-amd64.tar.gz"
  base="https://github.com/${INFRADESK_RELEASE_REPO}/releases/download/${tag}"
  log "Downloading ${archive} and SHA256SUMS from the ${tag} release..."
  curl -fsSL --max-time 600 -o "${WORK_DIR}/${archive}" "${base}/${archive}" &&
    curl -fsSL --max-time 60 -o "${WORK_DIR}/SHA256SUMS" "${base}/SHA256SUMS" ||
    die "Release ${tag} could not be downloaded from github.com/${INFRADESK_RELEASE_REPO}." \
      "If the repository is private, download both files with your GitHub account and use --from-file."
  ARCHIVE="${WORK_DIR}/${archive}"
  SUMS="${WORK_DIR}/SHA256SUMS"
}

# The checksum must be listed for exactly this archive name, and match.
verify_archive() {
  local archive="$1" sums="$2" name expected
  name="$(basename "${archive}")"
  expected="$(awk -v name="${name}" '$2 == name || $2 == "*" name { print $1 }' "${sums}")"
  [ "$(printf '%s\n' "${expected}" | grep -c .)" -eq 1 ] || die "${name} is not listed exactly once in $(basename "${sums}")."
  [ "$(sha256sum "${archive}" | awk '{ print $1 }')" = "${expected}" ] ||
    die "The checksum of ${name} does not match SHA256SUMS. The download is corrupt or not genuine; nothing was changed."
  log "Checksum verified: ${name}"
}

# Extracts into the work directory after checking that every entry stays inside one release folder.
extract_archive() {
  local archive="$1" top listing
  # Listed once into a variable: under pipefail, a reader that stops early (head, grep -q) would
  # make tar fail with SIGPIPE and turn a match into a silent exit or a missed violation.
  listing="$(tar -tzf "${archive}")" || die "The archive cannot be read."
  top="$(printf '%s\n' "${listing}" | awk 'NR == 1' | cut -d/ -f1)"
  [[ "${top}" =~ ^infradesk-v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "The archive does not contain an InfraDesk release folder."
  if [ -n "$(printf '%s\n' "${listing}" | grep -Ev "^${top}(/|$)" || true)" ] ||
    [ -n "$(printf '%s\n' "${listing}" | grep -E '(^|/)\.\.(/|$)' || true)" ]; then
    die "The archive contains paths outside its release folder; refusing to extract it."
  fi
  tar -xzf "${archive}" -C "${WORK_DIR}" --no-same-owner
  NEW_BUNDLE="${WORK_DIR}/${top}"
  [ -f "${NEW_BUNDLE}/release-manifest.json" ] && [ -x "${NEW_BUNDLE}/update.sh" ] ||
    die "The archive is not a complete InfraDesk release bundle."
}

# --- applying a verified bundle (runs from the new bundle) -------------------------------------

# Environment schema migrations: each step moves the configuration one installer schema forward,
# after a root-only copy of the file was taken. Schema 1 is the first; nothing to migrate yet.
migrate_configuration() {
  local from="$1" to="$2"
  [ "${from}" -eq "${to}" ] && return 0
  backup_env_file
  while [ "${from}" -lt "${to}" ]; do
    case "${from}" in
      *) die "No migration from installer schema ${from} is known to this release." ;;
    esac
  done
}

# Keys every release of this schema needs; a missing one with a safe default is added.
ensure_configuration() {
  local key domain
  domain="$(env_value INFRADESK_DOMAIN)"
  if [ -n "${domain}" ]; then
    valid_domain "${domain}" || die "INFRADESK_DOMAIN must be a DNS hostname. Nothing was changed."
    [ "$(env_value INFRADESK_HTTP_PUBLISH)" = '0.0.0.0:80' ] || die "Automatic HTTPS requires INFRADESK_HTTP_PUBLISH=0.0.0.0:80. Nothing was changed."
    [ "$(env_value INFRADESK_AUTH_COOKIE_SECURE)" = true ] || die "Automatic HTTPS requires secure authentication cookies. Nothing was changed."
  fi
  for key in INFRADESK_DB_PASSWORD INFRADESK_SECRET_MASTER_KEY_BASE64 INFRADESK_HTTP_PUBLISH \
    INFRADESK_INTERNAL_SUBNET INFRADESK_TRUSTED_PROXY_CIDR; do
    [ -n "$(env_value "${key}")" ] || die "${INFRADESK_ENV_FILE} has no ${key}; it cannot be generated again." \
      "Restore the file from your backup. Nothing was changed."
  done
  if [ -z "$(env_value INFRADESK_AUTH_COOKIE_SECURE)" ]; then
    backup_env_file
    env_ensure INFRADESK_AUTH_COOKIE_SECURE true
  fi
}

apply() {
  local bundle="$1" manifest current new new_sha current_schema new_schema backup url
  manifest="${bundle}/release-manifest.json"
  current="$(state_value VERSION)"
  new="v$(manifest_value "${manifest}" version)"
  new_sha="$(manifest_value "${manifest}" gitSha)"
  valid_release_version "${new}" || die "The bundle names an invalid version: ${new}"
  if [ "${new}" = "${current}" ]; then log "InfraDesk is already at ${current}. Nothing to do."; return 0; fi
  version_ge "${new}" "${current}" || die "${new} is older than the installed ${current}." \
    "Downgrades are not supported: a newer release may already have migrated the database." \
    "To go back, reinstall the older release and restore a backup taken with it. Nothing was changed."
  current_schema="$(state_value INSTALLER_SCHEMA_VERSION)"
  new_schema="$(manifest_value "${manifest}" installerSchemaVersion)"
  [ "${new_schema}" -ge "${current_schema}" ] || die "This bundle uses an older installer schema; nothing was changed."

  log "Updating InfraDesk ${current} -> ${new}"
  check_platform
  check_docker no no
  check_resources
  migrate_configuration "${current_schema}" "${new_schema}"
  ensure_configuration
  check_env_permissions || die "${INFRADESK_ENV_FILE} must be owned by root with mode 600."

  log "[1/5] Backing up the database"
  backup="$(backup_database "pre-update-${new}")" || die "The pre-update backup failed. The update was not started; nothing was changed."
  log "Backup: ${backup}"

  log "[2/5] Pulling ${new} images"
  compose_in "${bundle}" pull --quiet || die "The ${new} images could not be pulled." \
    "InfraDesk ${current} keeps running; nothing was changed."

  log "[3/5] Installing ${new} files"
  state_set "STATUS=updating" "PREVIOUS_VERSION=${current}"
  install_bundle_files "${bundle}"
  state_set "VERSION=${new}" "GIT_SHA=${new_sha}" "INSTALLER_SCHEMA_VERSION=${new_schema}"

  log "[4/5] Starting ${new}"
  # Only services whose image or settings changed are recreated; the data directory stays.
  compose up -d --remove-orphans >/dev/null 2>&1 || true
  url="$(base_url)"
  wait_ready "${url}" || die "InfraDesk ${new} did not become ready within ${INFRADESK_READY_TIMEOUT_SECONDS} seconds." \
    "Your data and configuration were preserved. The database may already be migrated to ${new}," \
    "so do not start ${current} again against it." "" "Check:" "  infradesk logs backend" "  infradesk doctor" "" \
    "To return to ${current}: reinstall its bundle and restore the backup taken before this update:" \
    "  sudo infradesk restore ${backup}"

  log "[5/5] Checking ${new}"
  smoke_test "${url}" "${new}" "${new_sha}" || die "InfraDesk ${new} is ready, but the smoke check failed." \
    "Check: infradesk doctor. The pre-update backup is ${backup}."
  state_set "STATUS=installed" "UPDATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  log ""
  log "InfraDesk was updated to ${new} (schema V$(db_schema_version))."
  log "Pre-update backup: ${backup}"
}

main() {
  local target="" from_file="" sums_file="" apply_bundle=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --from-file) from_file="${2:-}"; shift 2 ;;
      --sha256sums) sums_file="${2:-}"; shift 2 ;;
      --apply) apply_bundle="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      v*) target="$1"; shift ;;
      *) usage >&2; die "Unknown argument: $1" ;;
    esac
  done
  require_root
  installed || [ "$(state_value STATUS)" = "updating" ] || die "InfraDesk is not installed on this host."

  if [ -n "${apply_bundle}" ]; then
    apply "${apply_bundle}"
    return 0
  fi

  WORK_DIR="$(mktemp -d /tmp/infradesk-update.XXXXXX)"
  if [ -n "${from_file}" ]; then
    [ -n "${sums_file}" ] || die "--from-file needs --sha256sums with the SHA256SUMS of the same release."
    [ -f "${from_file}" ] || die "No such file: ${from_file}"
    [ -f "${sums_file}" ] || die "No such file: ${sums_file}"
    ARCHIVE="${from_file}"
    SUMS="${sums_file}"
  else
    if [ -z "${target}" ]; then target="$(latest_release)"; fi
    valid_release_version "${target}" || die "Not a release version: ${target} (expected vMAJOR.MINOR.PATCH)."
    download_release "${target}"
  fi
  verify_archive "${ARCHIVE}" "${SUMS}"
  extract_archive "${ARCHIVE}"
  if [ -n "${target}" ] && [ "v$(manifest_value "${NEW_BUNDLE}/release-manifest.json" version)" != "${target}" ]; then
    die "The downloaded bundle is not ${target}."
  fi
  # The new release knows its own migrations: its update logic applies it.
  "${NEW_BUNDLE}/update.sh" --apply "${NEW_BUNDLE}"
}

main "$@"
