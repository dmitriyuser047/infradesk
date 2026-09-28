#!/usr/bin/env bash
# Takes a consistent PostgreSQL backup of InfraDesk: a custom-format pg_dump that is read back
# before it counts, plus a metadata file with the version, commit and schema it belongs to.
#
#   sudo infradesk backup
#
# The configuration file with the encryption key is deliberately not copied next to the dump.
set -Eeuo pipefail

# shellcheck source=lib/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

main() {
  local path
  case "${1:-}" in
    -h|--help) log "Usage: sudo infradesk backup"; exit 0 ;;
    "") ;;
    *) die "Unknown option: $1" ;;
  esac
  require_root
  [ -f "${INFRADESK_STATE_FILE}" ] || die "InfraDesk is not installed on this host."
  log "Backing up the InfraDesk database..."
  path="$(backup_database infradesk)" || exit 1
  log "Backup written: ${path}"
  log "Metadata:       ${path}.meta"
  env_backup_warning
}

main "$@"
