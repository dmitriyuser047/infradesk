#!/usr/bin/env bash
# Installs InfraDesk from this release bundle: checks the host, writes the configuration once,
# pulls the pinned images, starts the topology and waits until it answers.
#
#   sudo ./install.sh                      interactive
#   sudo ./install.sh --non-interactive --admin-email admin@example.com \
#        --admin-password-file /root/infradesk-admin-password
#
# Running it again on an installed host changes nothing: no secret is regenerated, no data is
# touched. Nothing here ever deletes a database, a volume or a file it did not create.
set -Eeuo pipefail

BUNDLE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "${BUNDLE_DIR}/lib/common.sh"

TOTAL_PHASES=8
BIND_ADDRESS="127.0.0.1"
HTTP_PORT="8080"
DOMAIN=""
INTERACTIVE="auto"
INSTALL_DOCKER="no"
RECONCILE="no"
HTTPS="auto"
ADMIN_EMAIL=""
ADMIN_NAME=""
ORGANIZATION_NAME=""
ADMIN_PASSWORD_FILE=""
ADMIN_PASSWORD=""
GENERATED_PASSWORD="no"
DATABASE_CREATED_NOW="no"
TEMP_FILES=()

usage() {
  cat <<'EOF'
Usage: sudo ./install.sh [options]

  --bind ADDRESS              Address to publish InfraDesk on (default 127.0.0.1)
  --port PORT                 Port to publish InfraDesk on (default 8080)
  --domain HOSTNAME           Caddy automatic HTTPS; publishes ports 80 and 443
  --no-https                  Users reach InfraDesk over plain HTTP (testing only)
  --admin-email EMAIL         First administrator's email
  --admin-name NAME           First administrator's display name (default Administrator)
  --organization-name NAME    First organization (default "Default organization")
  --admin-password-file FILE  Read the administrator password from FILE (non-interactive)
  --non-interactive           Never prompt; without a password source a temporary password is generated
  --install-docker            Install Docker from the official Docker repository if it is missing
  --reconcile                 On an installed host: re-run checks and start InfraDesk, changing no data
  -h, --help                  Show this help

For automation, the password may also come from the INFRADESK_ADMIN_PASSWORD environment
variable. It is never accepted as a command-line argument.
EOF
}

cleanup() {
  local file
  for file in "${TEMP_FILES[@]}"; do rm -f "${file}"; done
  # The bootstrap file carries the first password: it never outlives this run.
  rm -f "${INFRADESK_BOOTSTRAP_FILE}"
}
trap cleanup EXIT

parse_arguments() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --bind) BIND_ADDRESS="${2:-}"; shift 2 ;;
      --port) HTTP_PORT="${2:-}"; shift 2 ;;
      --domain) DOMAIN="${2:-}"; valid_domain "${DOMAIN}" || die "--domain must be a DNS hostname." "No changes were made."; shift 2 ;;
      --no-https) HTTPS="no"; shift ;;
      --admin-email) ADMIN_EMAIL="${2:-}"; shift 2 ;;
      --admin-name) ADMIN_NAME="${2:-}"; shift 2 ;;
      --organization-name) ORGANIZATION_NAME="${2:-}"; shift 2 ;;
      --admin-password-file) ADMIN_PASSWORD_FILE="${2:-}"; shift 2 ;;
      --non-interactive) INTERACTIVE="no"; shift ;;
      --install-docker) INSTALL_DOCKER="yes"; shift ;;
      --reconcile) RECONCILE="yes"; shift ;;
      -h|--help) usage; exit 0 ;;
      *) usage >&2; die "Unknown option: $1" ;;
    esac
  done
  if [ "${INTERACTIVE}" = "auto" ]; then
    if [ -t 0 ]; then INTERACTIVE="yes"; else INTERACTIVE="no"; fi
  fi
  valid_ipv4 "${BIND_ADDRESS}" || die "--bind must be an IPv4 address, for example 127.0.0.1 or 0.0.0.0." "No changes were made."
  valid_port "${HTTP_PORT}" || die "--port must be a number from 1 to 65535." "No changes were made."
  if [ -n "${DOMAIN}" ]; then
    [ "${HTTPS}" != "no" ] || die "--domain cannot be combined with --no-https." "No changes were made."
    BIND_ADDRESS="0.0.0.0"
    HTTP_PORT="80"
    HTTPS="yes"
  fi
}

bundle_file() { printf '%s/%s' "${BUNDLE_DIR}" "$1"; }

read_bundle_identity() {
  local manifest
  manifest="$(bundle_file release-manifest.json)"
  [ -f "${manifest}" ] || die "This directory is not a complete InfraDesk release bundle (release-manifest.json is missing)."
  RELEASE_VERSION="$(manifest_value "${manifest}" version)"
  RELEASE_SHA="$(manifest_value "${manifest}" gitSha)"
  valid_release_version "v${RELEASE_VERSION}" || die "The release manifest names an invalid version: ${RELEASE_VERSION}"
  grep -q '@@' "$(bundle_file compose.yml)" && die "compose.yml in this bundle was never rendered with image references."
  return 0
}

# --- phase 1: checks ---------------------------------------------------------------------------

already_installed_notice() {
  log ""
  log "InfraDesk is already installed."
  log ""
  log "Version: $(state_value VERSION)"
  log "URL:     $(base_url)"
  log ""
  log "Use:"
  log "  infradesk status"
  log "  infradesk update"
  log ""
  log "Nothing was changed. To re-run the checks and start InfraDesk again: sudo ./install.sh --reconcile"
}

check_existing_paths() {
  # A configuration without our marker, or data without a configuration, is not ours to guess.
  if [ -f "${INFRADESK_ENV_FILE}" ] && [ -z "$(env_value INFRADESK_INSTALLER_SCHEMA_VERSION)" ]; then
    die "${INFRADESK_ENV_FILE} exists but was not created by this installer." \
      "Move it away or remove it yourself if it is not needed. No changes were made."
  fi
  if [ ! -f "${INFRADESK_ENV_FILE}" ] && [ -d "${INFRADESK_PG_DATA_DIR}" ] && [ -n "$(ls -A "${INFRADESK_PG_DATA_DIR}")" ]; then
    die "Database files exist in ${INFRADESK_PG_DATA_DIR}, but ${INFRADESK_ENV_FILE} is missing." \
      "Restore the configuration file from your backup first: it holds the database password and" \
      "the encryption key for this data. No changes were made."
  fi
  if [ -d "${INFRADESK_APP_DIR}" ] && [ ! -f "${INFRADESK_STATE_FILE}" ] && [ -n "$(ls -A "${INFRADESK_APP_DIR}")" ]; then
    die "${INFRADESK_APP_DIR} contains files that were not installed by InfraDesk." \
      "Move them away and run the installer again. No changes were made."
  fi
}

# The first administrator is created once. A database kept from an earlier installation already
# has its administrators, so bootstrap is skipped for it.
bootstrap_needed() {
  [ -z "$(state_value ADMIN_BOOTSTRAPPED)" ] || return 1
  [ ! -f "${INFRADESK_PG_DATA_DIR}/PG_VERSION" ] || [ "$(state_value DATABASE_CREATED)" = "yes" ]
}

collect_inputs() {
  local answer confirm
  if ! bootstrap_needed; then return 0; fi
  if [ "${INTERACTIVE}" = "yes" ]; then
    if [ -z "${DOMAIN}" ]; then
      read -r -p "Public bind address [${BIND_ADDRESS}]: " answer
      BIND_ADDRESS="${answer:-${BIND_ADDRESS}}"
      valid_ipv4 "${BIND_ADDRESS}" || die "The bind address must be an IPv4 address." "No changes were made."
      read -r -p "Public HTTP port [${HTTP_PORT}]: " answer
      HTTP_PORT="${answer:-${HTTP_PORT}}"
      valid_port "${HTTP_PORT}" || die "The port must be a number from 1 to 65535." "No changes were made."
    fi
    if [ "${HTTPS}" = "auto" ]; then
      read -r -p "Will users reach InfraDesk over HTTPS through a reverse proxy? [Y/n] " answer
      case "${answer}" in n|N|no|NO) HTTPS="no" ;; *) HTTPS="yes" ;; esac
    fi
    while ! valid_email "${ADMIN_EMAIL}"; do
      read -r -p "Admin email: " ADMIN_EMAIL
    done
    read -r -p "Admin display name [${ADMIN_NAME:-Administrator}]: " answer
    ADMIN_NAME="${answer:-${ADMIN_NAME:-Administrator}}"
    read -r -p "Organization name [${ORGANIZATION_NAME:-Default organization}]: " answer
    ORGANIZATION_NAME="${answer:-${ORGANIZATION_NAME:-Default organization}}"
    if [ -z "${ADMIN_PASSWORD_FILE}" ] && [ -z "${INFRADESK_ADMIN_PASSWORD:-}" ]; then
      while true; do
        read -r -s -p "Admin password (12-128 characters): " ADMIN_PASSWORD; echo
        read -r -s -p "Confirm password: " confirm; echo
        if [ "${ADMIN_PASSWORD}" != "${confirm}" ]; then log "The passwords do not match."; continue; fi
        valid_password "${ADMIN_PASSWORD}" && break
        log "Use 12 to 128 characters without control characters."
      done
    fi
  fi
  [ "${HTTPS}" = "auto" ] && HTTPS="yes"
  ADMIN_NAME="${ADMIN_NAME:-Administrator}"
  ORGANIZATION_NAME="${ORGANIZATION_NAME:-Default organization}"
  valid_email "${ADMIN_EMAIL}" || die "An administrator email is required: --admin-email you@example.com" "No changes were made."
  valid_display_text "${ADMIN_NAME}" || die "The administrator name must be one line of at most 255 characters." "No changes were made."
  valid_display_text "${ORGANIZATION_NAME}" || die "The organization name must be one line of at most 255 characters." "No changes were made."
  if [ -z "${ADMIN_PASSWORD}" ]; then
    if [ -n "${ADMIN_PASSWORD_FILE}" ]; then
      [ -f "${ADMIN_PASSWORD_FILE}" ] || die "No such password file: ${ADMIN_PASSWORD_FILE}" "No changes were made."
      ADMIN_PASSWORD="$(head -n 1 "${ADMIN_PASSWORD_FILE}")"
    elif [ -n "${INFRADESK_ADMIN_PASSWORD:-}" ]; then
      ADMIN_PASSWORD="${INFRADESK_ADMIN_PASSWORD}"
    else
      ADMIN_PASSWORD="$(random_base64 18)"
      GENERATED_PASSWORD="yes"
    fi
  fi
  valid_password "${ADMIN_PASSWORD}" || die "The administrator password must be 12 to 128 characters without control characters." \
    "No changes were made."
}

phase_check() {
  phase 1 "${TOTAL_PHASES}" "Checking system"
  check_platform
  check_docker "${INSTALL_DOCKER}" "${INTERACTIVE}"
  check_resources
  have_command curl || die "curl is required: apt-get install curl" "No changes were made."
  have_command openssl || die "openssl is required: apt-get install openssl" "No changes were made."
  check_existing_paths
  collect_inputs
  # Our own proxy may already hold the port when an interrupted installation is resumed.
  if [ -f "${INFRADESK_ENV_FILE}" ]; then
    BIND_ADDRESS="$(env_value INFRADESK_HTTP_PUBLISH | cut -d: -f1)"
    HTTP_PORT="$(env_value INFRADESK_HTTP_PUBLISH | cut -d: -f2)"
    DOMAIN="$(env_value INFRADESK_DOMAIN)"
  fi
  if [ -n "${DOMAIN}" ]; then
    valid_domain "${DOMAIN}" || die "INFRADESK_DOMAIN must be a DNS hostname. No changes were made."
    [ "${BIND_ADDRESS}:${HTTP_PORT}" = '0.0.0.0:80' ] || die "Automatic HTTPS requires publication on 0.0.0.0:80. No changes were made."
  fi
  if [ -z "$(container_id proxy 2>/dev/null || true)" ]; then
    check_port "${BIND_ADDRESS}" "${HTTP_PORT}"
    if [ -n "${DOMAIN}" ]; then check_port 0.0.0.0 443; fi
  fi
  if [ "${BIND_ADDRESS}" = "0.0.0.0" ] && [ -z "${DOMAIN}" ]; then
    warn "InfraDesk will listen on every interface (0.0.0.0:${HTTP_PORT}). The installer does not change"
    warn "your firewall: make sure only trusted networks can reach this port, or use a reverse proxy with HTTPS."
  fi
  log "OK: ${PLATFORM_ID} ${PLATFORM_VERSION}, Docker $(docker_server_version), Compose $(compose_version)"
}

# --- phase 2: directories and files ------------------------------------------------------------

phase_directories() {
  phase 2 "${TOTAL_PHASES}" "Preparing directories"
  install -d -m 0700 -o root -g root "${INFRADESK_CONFIG_DIR}"
  install -d -m 0755 -o root -g root "${INFRADESK_DATA_DIR}"
  # PostgreSQL's own entrypoint takes ownership of the data directory on first start.
  install -d -m 0700 "${INFRADESK_PG_DATA_DIR}"
  # A database this installation creates gets its first administrator, even if a run is resumed.
  if [ -z "$(ls -A "${INFRADESK_PG_DATA_DIR}")" ]; then DATABASE_CREATED_NOW="yes"; fi
  install -d -m 0700 -o root -g root "${INFRADESK_BACKUP_DIR}"
  install_bundle_files "${BUNDLE_DIR}"
  if [ ! -f "${INFRADESK_STATE_FILE}" ]; then
    state_set "INSTALLER_SCHEMA_VERSION=${INFRADESK_INSTALLER_SCHEMA_VERSION}" "STATUS=installing" \
      "INSTALLED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  fi
  state_set "VERSION=v${RELEASE_VERSION}" "GIT_SHA=${RELEASE_SHA}"
  if [ "${DATABASE_CREATED_NOW}" = "yes" ]; then state_set "DATABASE_CREATED=yes"; fi
  log "Application files: ${INFRADESK_APP_DIR}; CLI: ${INFRADESK_CLI_PATH}"
}

# --- phase 3: configuration --------------------------------------------------------------------

phase_configuration() {
  local subnet cookie organization_id
  phase 3 "${TOTAL_PHASES}" "Generating configuration"
  if [ -f "${INFRADESK_ENV_FILE}" ]; then
    # Secrets are generated once in the lifetime of an installation, never again.
    log "Keeping the existing configuration in ${INFRADESK_ENV_FILE}."
  else
    subnet="$(pick_internal_subnet)" || die "No free private /24 subnet was found for the InfraDesk network." \
      "Set INFRADESK_INTERNAL_SUBNET yourself (see .env.example). No containers were started."
    if [ "${HTTPS}" = "yes" ]; then cookie=true; else cookie=false; fi
    write_private_file "${INFRADESK_ENV_FILE}" "# InfraDesk configuration, written by the installer. Root only (mode 600).
# Back this file up separately and securely: it holds the key that decrypts stored credentials.
INFRADESK_INSTALLER_SCHEMA_VERSION=${INFRADESK_INSTALLER_SCHEMA_VERSION}
INFRADESK_DB_PASSWORD=$(random_hex 32)
INFRADESK_SECRET_MASTER_KEY_BASE64=$(random_base64 32)
INFRADESK_HTTP_PUBLISH=${BIND_ADDRESS}:${HTTP_PORT}
INFRADESK_DOMAIN=${DOMAIN}
INFRADESK_AUTH_COOKIE_SECURE=${cookie}
INFRADESK_INTERNAL_SUBNET=${subnet}
INFRADESK_TRUSTED_PROXY_CIDR=$(subnet_gateway_cidr "${subnet}")
INFRADESK_NOTIFICATION_WEBHOOK_URL="
    log "Wrote ${INFRADESK_ENV_FILE} (root, mode 600) with newly generated secrets."
  fi
  check_env_permissions || die "${INFRADESK_ENV_FILE} must be owned by root with mode 600."
  state_set "BIND_ADDRESS=$(env_value INFRADESK_HTTP_PUBLISH | cut -d: -f1)" \
    "HTTP_PORT=$(env_value INFRADESK_HTTP_PUBLISH | cut -d: -f2)"
  if ! bootstrap_needed && [ -z "$(state_value ADMIN_BOOTSTRAPPED)" ]; then
    log "Reusing the existing InfraDesk database in ${INFRADESK_PG_DATA_DIR}; its accounts are kept."
    state_set "ADMIN_BOOTSTRAPPED=existing-database"
  fi
  if bootstrap_needed; then
    organization_id="$(state_value ORGANIZATION_ID)"
    organization_id="${organization_id:-$(random_uuid)}"
    state_set "ORGANIZATION_ID=${organization_id}" "ADMIN_EMAIL=${ADMIN_EMAIL}"
    # A separate root-only file, passed to Compose only for the first start and deleted after it.
    write_private_file "${INFRADESK_BOOTSTRAP_FILE}" "INFRADESK_BOOTSTRAP_EMAIL=${ADMIN_EMAIL}
INFRADESK_BOOTSTRAP_PASSWORD=${ADMIN_PASSWORD}
INFRADESK_BOOTSTRAP_ORGANIZATION_ID=${organization_id}
INFRADESK_BOOTSTRAP_ORGANIZATION_NAME=${ORGANIZATION_NAME}
INFRADESK_BOOTSTRAP_DISPLAY_NAME=${ADMIN_NAME}"
  fi
}

# --- phases 4 to 8 -----------------------------------------------------------------------------

compose_first_start() {
  if [ -f "${INFRADESK_BOOTSTRAP_FILE}" ]; then
    docker compose --project-name "${INFRADESK_PROJECT}" --env-file "${INFRADESK_ENV_FILE}" \
      --env-file "${INFRADESK_BOOTSTRAP_FILE}" --file "${INFRADESK_APP_DIR}/compose.yml" "$@"
  else
    compose "$@"
  fi
}

failure_help() {
  die "$1" "Your database and configuration were preserved." "" "Check:" "  infradesk logs backend" "  infradesk doctor" \
    "" "Then run the installer again; it resumes with the same configuration."
}

phase_pull() {
  phase 4 "${TOTAL_PHASES}" "Pulling InfraDesk images"
  compose pull --quiet || die "The InfraDesk images could not be pulled." \
    "Check the network connection and access to the image registry." \
    "The configuration in ${INFRADESK_CONFIG_DIR} was kept and no container was started." \
    "Run the installer again once the registry is reachable."
}

phase_postgres() {
  phase 5 "${TOTAL_PHASES}" "Starting PostgreSQL"
  compose up -d postgres >/dev/null || failure_help "PostgreSQL could not be started."
  wait_postgres_healthy || failure_help "PostgreSQL did not become healthy within 120 seconds."
}

phase_start() {
  phase 6 "${TOTAL_PHASES}" "Starting InfraDesk"
  # The backend applies database migrations itself before it serves anything.
  compose_first_start up -d >/dev/null 2>&1 || true
}

phase_ready() {
  phase 7 "${TOTAL_PHASES}" "Waiting for readiness"
  wait_ready "$(base_url)" || failure_help "InfraDesk did not become ready within ${INFRADESK_READY_TIMEOUT_SECONDS} seconds."
  if [ -f "${INFRADESK_BOOTSTRAP_FILE}" ]; then
    # Ready means the first administrator exists. Start again without the password.
    state_set "ADMIN_BOOTSTRAPPED=yes"
    rm -f "${INFRADESK_BOOTSTRAP_FILE}"
    compose up -d >/dev/null 2>&1 || true
    wait_ready "$(base_url)" || failure_help "InfraDesk did not become ready again after the first start."
  fi
  log "InfraDesk is ready."
}

phase_smoke() {
  phase 8 "${TOTAL_PHASES}" "Running smoke check"
  smoke_test "$(base_url)" "v${RELEASE_VERSION}" "${RELEASE_SHA}" ||
    failure_help "InfraDesk started, but the smoke check through $(base_url) failed."
  state_set "STATUS=installed" "UPDATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

summary() {
  local host url
  host="$(env_value INFRADESK_HTTP_PUBLISH | cut -d: -f1)"
  url="$(base_url)"
  log ""
  log "InfraDesk installed successfully."
  log ""
  log "Version:  ${RELEASE_VERSION}"
  if [ -n "$(env_value INFRADESK_DOMAIN)" ]; then
    log "URL:      ${url} (Caddy automatic HTTPS)"
  elif [ "${host}" = "0.0.0.0" ]; then
    log "URL:      http://SERVER_IP:$(state_value HTTP_PORT)  (and ${url} on this host)"
  else
    log "URL:      ${url}"
  fi
  log "Admin:    $(state_value ADMIN_EMAIL)"
  if [ "${GENERATED_PASSWORD}" = "yes" ]; then
    # Shown once, to this terminal only; it is not written to any file.
    log "Password: ${ADMIN_PASSWORD}   (temporary: change it after signing in, under Account)"
  fi
  log ""
  if [ -z "$(env_value INFRADESK_DOMAIN)" ]; then
    log "Caddy serves InfraDesk. For HTTPS, configure a host-side Caddy proxy as described in"
    log "${INFRADESK_APP_DIR}/README.md, or use --domain on a fresh installation."
  fi
  log ""
  log "Next:"
  log "  1. Open InfraDesk."
  log "  2. Sign in."
  log "  3. Add your first SSH connection."
  log ""
  log "Manage it with: infradesk status | logs | backup | update | doctor"
  env_backup_warning
}

main() {
  parse_arguments "$@"
  require_root
  read_bundle_identity
  if installed && [ "${RECONCILE}" != "yes" ]; then
    local answer="n"
    if [ "${INTERACTIVE}" = "yes" ]; then read -r -p "InfraDesk is already installed. Re-run installation checks? [y/N] " answer; fi
    case "${answer}" in y|Y|yes|YES) RECONCILE="yes" ;; *) already_installed_notice; exit 0 ;; esac
  fi
  if installed && [ "$(state_value VERSION)" != "v${RELEASE_VERSION}" ]; then
    die "InfraDesk $(state_value VERSION) is installed; this bundle is v${RELEASE_VERSION}." \
      "Use 'infradesk update --from-file' or 'infradesk update v${RELEASE_VERSION}' to change versions. No changes were made."
  fi
  phase_check
  phase_directories
  phase_configuration
  phase_pull
  phase_postgres
  phase_start
  phase_ready
  phase_smoke
  summary
}

main "$@"
