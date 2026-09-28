#!/usr/bin/env bash
# Removes InfraDesk from this host.
#
#   sudo infradesk uninstall                 stop and remove the containers, the CLI and the
#                                            application files; KEEP all data
#   sudo infradesk uninstall --purge-data    also delete the database and the configuration
#                                            (asks twice; backups are always kept)
#
# Only paths this installer owns are touched, each checked before removal. No Docker prune, no
# volume removal by pattern, nothing outside the InfraDesk project.
set -Eeuo pipefail

# shellcheck source=lib/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

usage() {
  cat <<'EOF'
Usage: sudo infradesk uninstall [--purge-data] [--yes] [--confirm-hostname HOSTNAME]

  --purge-data               Also delete the database and the configuration (encryption key).
  --yes                      Do not ask before removing the application (data is still kept).
  --confirm-hostname NAME    With --purge-data and --yes: this host's name, as the second confirmation.
EOF
}

remove_application() {
  local file
  if [ -f "${INFRADESK_APP_DIR}/compose.yml" ] && [ -f "${INFRADESK_ENV_FILE}" ]; then
    # Containers and the private network of the project only; the data directory is a host path
    # and is not affected.
    compose down --remove-orphans >/dev/null 2>&1 || warn "The InfraDesk containers could not all be removed."
  fi
  if [ -f "${INFRADESK_CLI_PATH}" ] && grep -q 'InfraDesk self-hosted command line' "${INFRADESK_CLI_PATH}"; then
    rm -f "${INFRADESK_CLI_PATH}"
  fi
  for file in "${INFRADESK_BUNDLE_FILES[@]}" install-state; do
    rm -f "${INFRADESK_APP_DIR:?}/${file}"
  done
  rmdir "${INFRADESK_APP_DIR}/lib" 2>/dev/null || true
  if ! rmdir "${INFRADESK_APP_DIR}" 2>/dev/null; then
    warn "${INFRADESK_APP_DIR} still contains files InfraDesk did not install; they were left in place."
  fi
}

purge_data() {
  local file
  # The database directory is removed only at its fixed path, and only if it looks like one.
  if [ -d "${INFRADESK_PG_DATA_DIR}" ]; then
    if [ -f "${INFRADESK_PG_DATA_DIR}/PG_VERSION" ] || [ -z "$(ls -A "${INFRADESK_PG_DATA_DIR}")" ]; then
      rm -rf --one-file-system "${INFRADESK_PG_DATA_DIR:?}"
    else
      warn "${INFRADESK_PG_DATA_DIR} does not look like an InfraDesk database; it was left in place."
    fi
  fi
  rmdir "${INFRADESK_DATA_DIR}" 2>/dev/null || true
  rm -f "${INFRADESK_ENV_FILE}" "${INFRADESK_BOOTSTRAP_FILE}"
  for file in "${INFRADESK_ENV_FILE}".bak-*; do
    [ -f "${file}" ] && rm -f "${file}"
  done
  rmdir "${INFRADESK_CONFIG_DIR}" 2>/dev/null || true
}

main() {
  local purge="no" assume_yes="no" hostname_confirmation="" answer
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --purge-data) purge="yes"; shift ;;
      --yes) assume_yes="yes"; shift ;;
      --confirm-hostname) hostname_confirmation="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) usage >&2; die "Unknown option: $1" ;;
    esac
  done
  require_root
  [ -f "${INFRADESK_STATE_FILE}" ] && grep -q '^INSTALLER_SCHEMA_VERSION=' "${INFRADESK_STATE_FILE}" ||
    die "No InfraDesk installation was found at ${INFRADESK_APP_DIR}; nothing was removed."

  if [ "${purge}" = "yes" ]; then
    log "This PERMANENTLY DELETES the InfraDesk database (${INFRADESK_PG_DATA_DIR}) and the"
    log "configuration with the encryption key (${INFRADESK_ENV_FILE}). Backups in"
    log "${INFRADESK_BACKUP_DIR} are kept, but they cannot restore connection credentials without that key."
    if [ "${assume_yes}" = "yes" ]; then
      [ "${hostname_confirmation}" = "$(hostname)" ] ||
        die "--purge-data --yes also needs --confirm-hostname $(hostname). Nothing was removed."
    else
      [ -t 0 ] || die "Purging needs an interactive confirmation (or --yes --confirm-hostname). Nothing was removed."
      read -r -p "Type 'delete all data' to continue: " answer
      [ "${answer}" = "delete all data" ] || die "Uninstall cancelled. Nothing was removed."
      read -r -p "Type this host's name ($(hostname)) to confirm: " answer
      [ "${answer}" = "$(hostname)" ] || die "Uninstall cancelled. Nothing was removed."
    fi
  elif [ "${assume_yes}" != "yes" ]; then
    [ -t 0 ] || die "Confirmation is required; re-run with --yes. Nothing was removed."
    read -r -p "Remove InfraDesk from this host? Data is kept. [y/N] " answer
    case "${answer}" in y|Y|yes|YES) ;; *) die "Uninstall cancelled. Nothing was removed." ;; esac
  fi

  remove_application
  if [ "${purge}" = "yes" ]; then
    purge_data
    log "InfraDesk and its data were removed. Backups were kept in ${INFRADESK_BACKUP_DIR}."
  else
    log "InfraDesk was removed."
    log ""
    log "Data was preserved:"
    log "  Database:      ${INFRADESK_PG_DATA_DIR}"
    log "  Configuration: ${INFRADESK_ENV_FILE}"
    log "  Backups:       ${INFRADESK_BACKUP_DIR}"
    log "Installing the same or a newer release again reuses it."
  fi
}

main "$@"
