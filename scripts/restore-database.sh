#!/usr/bin/env bash
# Restores one dump into the database of a stopped deployment.
#
# Usage: scripts/restore-database.sh /var/backups/infradesk/infradesk-...dump
#
# The application must be down while this runs, and it has to come back up with the same
# INFRADESK_SECRET_MASTER_KEY_BASE64 the dump was taken under — without it the stored connection
# secrets cannot be decrypted, however complete the data is.
set -euo pipefail

dump="${1:?usage: restore-database.sh <dump-file>}"
COMPOSE_FILE="${INFRADESK_COMPOSE_FILE:-compose.prod.yml}"
ENV_FILE="${INFRADESK_ENV_FILE:-}"

[ -f "${dump}" ] || { echo "no such dump: ${dump}" >&2; exit 1; }

compose=(docker compose)
if [ -n "${ENV_FILE}" ]; then
  [ -f "${ENV_FILE}" ] || { echo "no such environment file: ${ENV_FILE}" >&2; exit 1; }
  compose+=(--env-file "${ENV_FILE}")
fi
compose+=(-f "${COMPOSE_FILE}")

"${compose[@]}" up -d postgres
"${compose[@]}" stop backend proxy || true

# A restore replaces the schema it finds; Flyway then brings it to the version the image needs.
"${compose[@]}" exec -T postgres \
  sh -c 'exec pg_restore --clean --if-exists --no-owner --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"' \
  < "${dump}"

"${compose[@]}" up -d
echo "restored ${dump}; verify with scripts/check-production.sh"
