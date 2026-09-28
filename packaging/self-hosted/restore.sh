#!/usr/bin/env bash
# DESTRUCTIVE: replaces the InfraDesk database with a backup.
#
#   sudo infradesk restore /var/backups/infradesk/infradesk-20260928-120000.dump [--yes]
#
# The file is verified and its schema compared with this release before anything changes. The
# application is stopped (PostgreSQL keeps running), an emergency backup of the current database is
# taken, the database is recreated from the dump, and the backend migrates it forward on start.
set -Eeuo pipefail

# shellcheck source=lib/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

usage() { log "Usage: sudo infradesk restore FILE [--yes]"; }

main() {
  local file="" assume_yes="no" supported backup_schema answer emergency database url
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --yes) assume_yes="yes"; shift ;;
      -h|--help) usage; exit 0 ;;
      -*) usage >&2; die "Unknown option: $1" ;;
      *) [ -z "${file}" ] || die "Only one backup file can be restored."; file="$1"; shift ;;
    esac
  done
  [ -n "${file}" ] || { usage >&2; exit 2; }
  require_root
  [ -f "${INFRADESK_STATE_FILE}" ] || die "InfraDesk is not installed on this host."
  [ -f "${file}" ] || die "No such file: ${file}"
  file="$(cd "$(dirname "${file}")" && pwd)/$(basename "${file}")"

  [ -n "$(container_id postgres)" ] || compose up -d postgres >/dev/null
  wait_postgres_healthy || die "PostgreSQL is not healthy; nothing was restored."
  dump_is_valid "${file}" || die "${file} is not a readable InfraDesk backup (pg_dump custom format)." "Nothing was changed."
  backup_schema="$(dump_schema_version "${file}")"
  [ -n "${backup_schema}" ] || die "${file} does not contain an InfraDesk database (no migration history)." "Nothing was changed."
  supported="$(manifest_value "${INFRADESK_APP_DIR}/release-manifest.json" schemaVersion)"
  if [ "${backup_schema}" -gt "${supported}" ]; then
    die "Backup requires schema V${backup_schema}." \
      "This InfraDesk release ($(state_value VERSION)) supports up to V${supported}." \
      "" "Install a newer InfraDesk release first. Nothing was changed."
  fi

  log "Current installation: InfraDesk $(state_value VERSION), schema V$(db_schema_version || printf '?')"
  log "Backup to restore:    ${file}"
  log "Backup schema:        V${backup_schema}"
  if [ -f "${file}.meta" ]; then
    log "Backup taken from:    $(env_value VERSION "${file}.meta") at $(env_value CREATED_AT "${file}.meta")"
  fi
  log ""
  log "This REPLACES the current InfraDesk database. Everything written since the backup is lost"
  log "(an emergency backup of the current database is taken first)."
  if [ "${assume_yes}" != "yes" ]; then
    [ -t 0 ] || die "Confirmation is required; re-run with --yes to restore non-interactively."
    read -r -p "Type 'restore' to continue: " answer
    [ "${answer}" = "restore" ] || die "Restore cancelled. Nothing was changed."
  fi

  log "Stopping the application (PostgreSQL keeps running)..."
  compose stop backend proxy >/dev/null
  emergency="$(backup_database pre-restore)" || {
    compose up -d >/dev/null 2>&1 || true
    die "The emergency backup failed; nothing was restored and InfraDesk was started again."
  }
  log "Emergency backup of the current database: ${emergency}"

  database="$(compose exec -T postgres sh -c 'printf "%s" "$POSTGRES_DB"')"
  case "${database}" in
    ""|postgres|template0|template1|-*) die "Refusing to replace the unsafe database name '${database}'." ;;
  esac
  log "Restoring ${file}..."
  compose exec -T postgres sh -c 'set -eu
    dropdb --force --if-exists --maintenance-db=postgres --username "$POSTGRES_USER" "$POSTGRES_DB"
    createdb --maintenance-db=postgres --username "$POSTGRES_USER" --owner "$POSTGRES_USER" "$POSTGRES_DB"' ||
    die "The database could not be recreated." "The emergency backup is ${emergency}."
  compose exec -T postgres sh -c 'exec pg_restore --exit-on-error --no-owner --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"' \
    < "${file}" || die "pg_restore failed." "Restore the emergency backup with: sudo infradesk restore ${emergency}"

  log "Starting InfraDesk..."
  compose up -d >/dev/null 2>&1 || true
  url="$(base_url)"
  wait_ready "${url}" || die "InfraDesk did not become ready after the restore." \
    "Check: infradesk logs backend" "The emergency backup of the previous database is ${emergency}."
  smoke_test "${url}" "$(state_value VERSION)" "$(state_value GIT_SHA)" ||
    die "InfraDesk is ready, but the smoke check failed. Check: infradesk doctor"
  log "Restored ${file}. InfraDesk is ready at ${url} (schema V$(db_schema_version))."
  log "Connections work only with the same ${INFRADESK_ENV_FILE} the backup was taken under."
}

main "$@"
