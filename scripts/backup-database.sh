#!/usr/bin/env bash
# Takes one compressed, timestamped dump of the InfraDesk database and prunes old ones.
#
# The dump restores the data, not the deployment: connection secrets stay encrypted with
# INFRADESK_SECRET_MASTER_KEY_BASE64, which is backed up separately (see docs/production.md).
set -euo pipefail

BACKUP_DIR="${INFRADESK_BACKUP_DIR:-/var/backups/infradesk}"
RETENTION_DAYS="${INFRADESK_BACKUP_RETENTION_DAYS:-14}"
COMPOSE_FILE="${INFRADESK_COMPOSE_FILE:-compose.prod.yml}"
ENV_FILE="${INFRADESK_ENV_FILE:-}"

compose=(docker compose)
if [ -n "${ENV_FILE}" ]; then
  [ -f "${ENV_FILE}" ] || { echo "no such environment file: ${ENV_FILE}" >&2; exit 1; }
  compose+=(--env-file "${ENV_FILE}")
fi
compose+=(-f "${COMPOSE_FILE}")

timestamp="$(date -u +%Y-%m-%dT%H%M%SZ)"
target="${BACKUP_DIR}/infradesk-${timestamp}.dump"

install -d -m 700 "${BACKUP_DIR}"

# The dump is written through the compose service, so no port has to be published and the
# password never appears in an argument list.
"${compose[@]}" exec -T postgres \
  sh -c 'exec pg_dump --format=custom --no-owner --username "$POSTGRES_USER" "$POSTGRES_DB"' \
  > "${target}.partial"

mv "${target}.partial" "${target}"
chmod 600 "${target}"
echo "wrote ${target}"

# Retention is a floor, not a guarantee: an off-host copy is what survives losing this machine.
find "${BACKUP_DIR}" -maxdepth 1 -name 'infradesk-*.dump' -type f -mtime "+${RETENTION_DAYS}" -print -delete
