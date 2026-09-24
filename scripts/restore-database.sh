#!/usr/bin/env bash
# DESTRUCTIVE: replaces the application database with one cleanly restored from a dump.
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

"${compose[@]}" stop backend proxy || true
"${compose[@]}" up -d postgres

database="$("${compose[@]}" exec -T postgres sh -c 'printf "%s" "$POSTGRES_DB"')"
case "${database}" in
  ""|postgres|template0|template1|-*)
    echo "refusing to replace unsafe application database name: ${database:-<empty>}" >&2
    exit 1
    ;;
esac

# Recreate the database instead of layering an old dump over a newer schema. The persistent
# PostgreSQL volume remains intact; only the named application database is replaced.
"${compose[@]}" exec -T postgres \
  sh -c 'set -eu
    dropdb --force --if-exists --maintenance-db=postgres --username "$POSTGRES_USER" "$POSTGRES_DB"
    createdb --maintenance-db=postgres --username "$POSTGRES_USER" --owner "$POSTGRES_USER" "$POSTGRES_DB"'

"${compose[@]}" exec -T postgres \
  sh -c 'exec pg_restore --exit-on-error --no-owner --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"' \
  < "${dump}"

"${compose[@]}" up -d
echo "restored ${dump}; verify with scripts/check-production.sh"
