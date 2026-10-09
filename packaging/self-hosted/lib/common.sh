# shellcheck shell=bash
# The constants and platform facts below are read by the scripts that source this library, which
# ShellCheck cannot see when it checks this file on its own.
# shellcheck disable=SC2034
# Shared functions of the InfraDesk self-hosted installer, CLI and lifecycle scripts.
#
# Sourced, never executed. Nothing here prints a secret: values from the environment file are read
# one key at a time, never echoed, and no function turns on shell tracing.

# --- fixed locations ---------------------------------------------------------------------------

INFRADESK_APP_DIR="/opt/infradesk"
INFRADESK_CONFIG_DIR="/etc/infradesk"
INFRADESK_ENV_FILE="/etc/infradesk/infradesk.env"
INFRADESK_BOOTSTRAP_FILE="/etc/infradesk/bootstrap.env"
INFRADESK_DATA_DIR="/var/lib/infradesk"
INFRADESK_PG_DATA_DIR="/var/lib/infradesk/postgres"
INFRADESK_BACKUP_DIR="/var/backups/infradesk"
INFRADESK_CLI_PATH="/usr/local/bin/infradesk"
INFRADESK_STATE_FILE="/opt/infradesk/install-state"
INFRADESK_PROJECT="infradesk"
INFRADESK_RELEASE_REPO="dmitriyuser047/infradesk"
INFRADESK_INSTALLER_SCHEMA_VERSION=1
INFRADESK_READY_TIMEOUT_SECONDS=180

# Everything a release bundle installs into the application directory; nothing else is ever
# written there, and uninstall removes exactly these.
INFRADESK_BUNDLE_FILES=(compose.yml compose.https.yml .env.example install.sh update.sh backup.sh restore.sh uninstall.sh
  infradesk VERSION README.md release-manifest.json lib/common.sh)

MIN_DOCKER_VERSION="24.0.0"
MIN_COMPOSE_VERSION="2.20.0"
MIN_MEMORY_MIB=1900
RECOMMENDED_MEMORY_MIB=3800
MIN_DISK_GIB=10

# --- output ------------------------------------------------------------------------------------

log() { printf '%s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die() {
  printf '\nERROR: %s\n' "$1" >&2
  shift
  local line
  for line in "$@"; do printf '%s\n' "${line}" >&2; done
  exit 1
}
phase() { printf '\n[%s/%s] %s\n' "$1" "$2" "$3"; }

require_root() {
  [ "$(id -u)" -eq 0 ] || die "This command must be run as root." "Run it with sudo."
}

have_command() { command -v "$1" >/dev/null 2>&1; }

# --- validation --------------------------------------------------------------------------------

valid_port() {
  [[ "$1" =~ ^[0-9]{1,5}$ ]] && [ "$1" -ge 1 ] && [ "$1" -le 65535 ]
}

valid_ipv4() {
  local IFS=. part
  [[ "$1" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]] || return 1
  # shellcheck disable=SC2086 # splitting on dots is the point here
  for part in $1; do [ "${part}" -le 255 ] || return 1; done
}

valid_email() {
  [[ "$1" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] && [ "${#1}" -le 254 ]
}

valid_release_version() {
  [[ "$1" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]
}

# One line of printable text, bounded; the same limits the backend applies to display names.
valid_display_text() {
  [ -n "$1" ] && [ "${#1}" -le 255 ] && ! [[ "$1" =~ [[:cntrl:]] ]]
}

# The backend's password policy: 12 to 128 characters, no control characters.
valid_password() {
  [ "${#1}" -ge 12 ] && [ "${#1}" -le 128 ] && ! [[ "$1" =~ [[:cntrl:]] ]]
}

# 0 when $1 >= $2 for dotted numeric versions (leading "v" and suffixes ignored).
version_ge() {
  local a b i x y
  IFS=. read -r -a a <<<"$(printf '%s' "${1#v}" | sed 's/[^0-9.].*$//')"
  IFS=. read -r -a b <<<"$(printf '%s' "${2#v}" | sed 's/[^0-9.].*$//')"
  for i in 0 1 2; do
    x="${a[i]:-0}"; y="${b[i]:-0}"
    x="${x:-0}"; y="${y:-0}"
    if [ "$((10#${x}))" -gt "$((10#${y}))" ]; then return 0; fi
    if [ "$((10#${x}))" -lt "$((10#${y}))" ]; then return 1; fi
  done
  return 0
}

# --- platform ----------------------------------------------------------------------------------

# Reads ID and VERSION_ID from an os-release file without executing it.
os_release_value() {
  local file="$1" key="$2"
  sed -n "s/^${key}=//p" "${file}" | awk 'NR == 1' | tr -d '"'"'"
}

# Prints "<id> <version> <arch>"; returns 0 only for a supported platform.
platform_supported() {
  local id="$1" version="$2" arch="$3"
  [ "${arch}" = "x86_64" ] || [ "${arch}" = "amd64" ] || return 1
  case "${id}:${version}" in
    ubuntu:22.04|ubuntu:24.04|ubuntu:26.04|debian:12) return 0 ;;
    *) return 1 ;;
  esac
}

# Only a DNS hostname, never Caddyfile syntax, a URL, IP or shell/environment expression.
valid_domain() {
  local domain="$1" label
  [ "${#domain}" -le 253 ] && [[ "${domain}" == *.* ]] || return 1
  [[ "${domain}" =~ ^[a-zA-Z0-9.-]+$ ]] || return 1
  [[ ! "${domain}" =~ ^[0-9.]+$ ]] || return 1
  local labels
  IFS=. read -r -a labels <<< "${domain}"
  [[ "${domain}" != *. ]] || return 1
  for label in "${labels[@]}"; do
    [ "${#label}" -le 63 ] && [[ "${label}" =~ ^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$ ]] || return 1
  done
}

check_platform() {
  local file="${1:-/etc/os-release}" arch="${2:-$(uname -m)}" id version pretty
  if [ -r "${file}" ]; then
    id="$(os_release_value "${file}" ID)"
    version="$(os_release_value "${file}" VERSION_ID)"
    pretty="$(os_release_value "${file}" PRETTY_NAME)"
  fi
  if ! platform_supported "${id:-unknown}" "${version:-unknown}" "${arch}"; then
    die "This platform is not supported." \
      "InfraDesk installer currently supports:" \
      "  - Ubuntu 22.04 / 24.04 / 26.04" \
      "  - Debian 12" \
      "  - linux/amd64" \
      "" \
      "Detected: ${pretty:-${id:-unknown} ${version:-}} / ${arch}" \
      "" \
      "No changes were made."
  fi
  PLATFORM_ID="${id}"
  PLATFORM_VERSION="${version}"
  PLATFORM_CODENAME="$(os_release_value "${file}" VERSION_CODENAME)"
}

check_resources() {
  local memory_kib memory_mib docker_root free_kib free_gib
  memory_kib="$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)"
  memory_mib=$((memory_kib / 1024))
  if [ "${memory_mib}" -lt "${MIN_MEMORY_MIB}" ]; then
    die "This host has ${memory_mib} MiB of memory; InfraDesk needs at least 2 GiB." "No changes were made."
  fi
  if [ "${memory_mib}" -lt "${RECOMMENDED_MEMORY_MIB}" ]; then
    warn "This host has ${memory_mib} MiB of memory; 4 GiB is recommended."
  fi
  docker_root="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || true)"
  for path in "${docker_root:-/var/lib/docker}" /var/lib; do
    [ -d "${path}" ] || continue
    free_kib="$(df -Pk "${path}" | awk 'NR == 2 { print $4 }')"
    free_gib=$((free_kib / 1024 / 1024))
    if [ "${free_gib}" -lt "${MIN_DISK_GIB}" ]; then
      die "Only ${free_gib} GiB are free under ${path}; InfraDesk needs at least ${MIN_DISK_GIB} GiB." "No changes were made."
    fi
  done
}

# --- docker ------------------------------------------------------------------------------------

docker_server_version() { docker version --format '{{.Server.Version}}' 2>/dev/null; }
compose_version() { docker compose version --short 2>/dev/null | sed 's/^v//'; }

# Installs Docker Engine from Docker's own apt repository, as documented for Ubuntu and Debian.
install_docker() {
  log "Installing Docker Engine from the official Docker repository (${PLATFORM_ID} ${PLATFORM_CODENAME})..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq ca-certificates curl
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL "https://download.docker.com/linux/${PLATFORM_ID}/gpg" -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  printf 'deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/%s %s stable\n' \
    "${PLATFORM_ID}" "${PLATFORM_CODENAME}" > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker >/dev/null 2>&1 || true
}

# $1: "yes" to install Docker if missing; $2: "yes" when a person can be asked.
check_docker() {
  local allow_install="$1" interactive="$2" answer server compose
  if ! have_command docker; then
    if [ "${allow_install}" = "yes" ]; then
      install_docker
    elif [ "${interactive}" = "yes" ]; then
      log ""
      log "Docker is required."
      log "  [1] Exit and install it manually (https://docs.docker.com/engine/install/)"
      log "  [2] Install Docker using the official Docker repository"
      read -r -p "Install Docker now? [y/N] " answer
      case "${answer}" in
        y|Y|yes|YES) install_docker ;;
        *) die "Docker is not installed." "Install Docker Engine and the Compose plugin, then run the installer again." "No changes were made." ;;
      esac
    else
      die "Docker is not installed." \
        "Install Docker Engine and the Compose plugin, or re-run with --install-docker." "No changes were made."
    fi
  fi
  server="$(docker_server_version || true)"
  [ -n "${server}" ] || die "The Docker daemon is not reachable." \
    "Start it (systemctl start docker) and run the installer again." "No changes were made."
  version_ge "${server}" "${MIN_DOCKER_VERSION}" || die "Docker ${server} is too old; ${MIN_DOCKER_VERSION} or newer is required." \
    "No changes were made."
  compose="$(compose_version || true)"
  [ -n "${compose}" ] || die "Docker Compose v2 (the 'docker compose' plugin) is not installed." \
    "Install docker-compose-plugin and run the installer again." "No changes were made."
  version_ge "${compose}" "${MIN_COMPOSE_VERSION}" || die "Docker Compose ${compose} is too old; ${MIN_COMPOSE_VERSION} or newer is required." \
    "No changes were made."
}

# --- ports and networks ------------------------------------------------------------------------

# Prints the listeners that would conflict with binding $1:$2, if any.
port_listeners() {
  local bind="$1" port="$2"
  have_command ss || return 0
  ss -Hltnp 2>/dev/null | awk -v port="${port}" -v bind="${bind}" '
    {
      local_address = $4
      n = split(local_address, parts, ":")
      if (parts[n] != port) next
      address = substr(local_address, 1, length(local_address) - length(port) - 1)
      gsub(/[\[\]]/, "", address)
      sub(/%.*/, "", address)
      if (bind == "0.0.0.0" || address == "0.0.0.0" || address == "*" || address == "::" || address == bind) print
    }'
}

check_port() {
  local bind="$1" port="$2" listeners
  listeners="$(port_listeners "${bind}" "${port}")"
  if [ -n "${listeners}" ]; then
    die "InfraDesk cannot bind ${bind}:${port}." \
      "A process is already listening there:" \
      "${listeners}" \
      "" \
      "Choose another port, for example:" \
      "  sudo ./install.sh --port $((port + 1))" \
      "" \
      "No containers were started."
  fi
}

ipv4_to_int() {
  local IFS=. a b c d
  read -r a b c d <<<"$1"
  printf '%s' "$(( (a << 24) + (b << 16) + (c << 8) + d ))"
}

# 0 when two IPv4 CIDRs overlap.
cidr_overlap() {
  local a="${1%/*}" am="${1#*/}" b="${2%/*}" bm="${2#*/}" m ai bi mask
  m=$(( am < bm ? am : bm ))
  ai="$(ipv4_to_int "${a}")"; bi="$(ipv4_to_int "${b}")"
  mask=$(( m == 0 ? 0 : (0xFFFFFFFF << (32 - m)) & 0xFFFFFFFF ))
  [ $(( ai & mask )) -eq $(( bi & mask )) ]
}

used_subnets() {
  local networks
  networks="$(docker network ls --quiet 2>/dev/null || true)"
  if [ -n "${networks}" ]; then
    # shellcheck disable=SC2086 # one argument per network id
    docker network inspect --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' ${networks} 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9.]+/[0-9]+$' || true
  fi
  if have_command ip; then
    ip -4 route show 2>/dev/null | awk '{ print $1 }' | grep -E '^[0-9.]+/[0-9]+$' || true
  fi
}

# The first private /24 no Docker network or host route already uses.
pick_internal_subnet() {
  local candidate used taken
  used="$(used_subnets)"
  for candidate in 172.28.0.0/24 172.29.0.0/24 172.30.0.0/24 172.31.0.0/24 10.213.0.0/24 10.214.0.0/24 192.168.213.0/24; do
    taken=no
    while IFS= read -r subnet; do
      [ -n "${subnet}" ] || continue
      if cidr_overlap "${candidate}" "${subnet}"; then taken=yes; break; fi
    done <<<"${used}"
    if [ "${taken}" = "no" ]; then printf '%s' "${candidate}"; return 0; fi
  done
  return 1
}

# The host-side gateway of a /24 subnet: the only address Caddy trusts for X-Forwarded-For.
subnet_gateway_cidr() {
  local base="${1%/*}"
  printf '%s.1/32' "${base%.*}"
}

# --- environment file --------------------------------------------------------------------------

# Reads one key without sourcing the file: the value is never executed or echoed.
env_value() {
  local file="${2:-${INFRADESK_ENV_FILE}}"
  [ -r "${file}" ] || return 0
  sed -n "s/^$1=//p" "${file}" | tail -n 1
}

# Writes a root-only file atomically: a temporary file in the same directory, then a rename.
write_private_file() {
  local target="$1" content="$2" temporary
  temporary="$(mktemp "$(dirname "${target}")/.tmp.XXXXXX")"
  chmod 600 "${temporary}"
  chown root:root "${temporary}"
  printf '%s\n' "${content}" > "${temporary}"
  mv -f "${temporary}" "${target}"
}

# A copy only root can read, taken before any change to the environment file.
backup_env_file() {
  local copy
  copy="${INFRADESK_ENV_FILE}.bak-$(date -u +%Y%m%d-%H%M%S)"
  install -m 600 -o root -g root "${INFRADESK_ENV_FILE}" "${copy}"
  log "Saved a root-only copy of the configuration: ${copy}"
}

# Adds a key with a default value when a newer release requires it; existing values are kept.
env_ensure() {
  local key="$1" value="$2"
  if ! grep -q "^${key}=" "${INFRADESK_ENV_FILE}"; then
    write_private_file "${INFRADESK_ENV_FILE}" "$(cat "${INFRADESK_ENV_FILE}")
${key}=${value}"
  fi
}

check_env_permissions() {
  local owner mode
  owner="$(stat -c '%U:%G' "${INFRADESK_ENV_FILE}")"
  mode="$(stat -c '%a' "${INFRADESK_ENV_FILE}")"
  [ "${owner}" = "root:root" ] && [ "${mode}" = "600" ]
}

random_hex() { openssl rand -hex "$1"; }
random_base64() { openssl rand -base64 "$1" | tr -d '\n'; }
random_uuid() { cat /proc/sys/kernel/random/uuid; }

# --- install state -----------------------------------------------------------------------------

state_value() { env_value "$1" "${INFRADESK_STATE_FILE}"; }

# Rewrites the state file with the given KEY=VALUE pairs replacing earlier ones. Never secrets.
state_set() {
  local temporary pair key
  install -d -m 0755 "${INFRADESK_APP_DIR}"
  temporary="$(mktemp "${INFRADESK_APP_DIR}/.state.XXXXXX")"
  if [ -f "${INFRADESK_STATE_FILE}" ]; then cp "${INFRADESK_STATE_FILE}" "${temporary}"; fi
  for pair in "$@"; do
    key="${pair%%=*}"
    sed -i "/^${key}=/d" "${temporary}"
    printf '%s\n' "${pair}" >> "${temporary}"
  done
  chmod 644 "${temporary}"
  mv -f "${temporary}" "${INFRADESK_STATE_FILE}"
}

installed() { [ -f "${INFRADESK_STATE_FILE}" ] && [ "$(state_value STATUS)" = "installed" ]; }

# --- bundle files ------------------------------------------------------------------------------

# Copies a bundle's files into the application directory and the CLI into place. Every file is
# staged first and then replaced by a rename, so no reader ever sees half of a new bundle.
install_bundle_files() {
  local source="$1" staging file
  install -d -m 0755 -o root -g root "${INFRADESK_APP_DIR}" "${INFRADESK_APP_DIR}/lib"
  staging="$(mktemp -d "${INFRADESK_APP_DIR}/.staging.XXXXXX")"
  for file in "${INFRADESK_BUNDLE_FILES[@]}"; do
    install -D -m 0644 -o root -g root "${source}/${file}" "${staging}/${file}"
  done
  chmod 0755 "${staging}"/*.sh "${staging}/infradesk"
  for file in "${INFRADESK_BUNDLE_FILES[@]}"; do
    mv -f "${staging}/${file}" "${INFRADESK_APP_DIR}/${file}"
  done
  rm -rf "${staging}"
  install -m 0755 -o root -g root "${INFRADESK_APP_DIR}/infradesk" "${INFRADESK_CLI_PATH}.tmp"
  mv -f "${INFRADESK_CLI_PATH}.tmp" "${INFRADESK_CLI_PATH}"
}

# --- release manifest --------------------------------------------------------------------------

# Reads a top-level string or number from a release-manifest.json written one key per line.
manifest_value() {
  local file="$1" key="$2"
  sed -n "s/^[[:space:]]*\"${key}\":[[:space:]]*\"\{0,1\}\([^\",]*\)\"\{0,1\},\{0,1\}[[:space:]]*$/\1/p" "${file}" | awk 'NR == 1'
}

# --- compose -----------------------------------------------------------------------------------

# Every compose call goes through here: the fixed project name, compose file and environment file,
# so the working directory never matters. Extra env files (bootstrap) come first in "$@" as
# --env-file pairs when needed.
compose_in() {
  local dir="$1"; shift
  local extra=() domain
  domain="$(env_value INFRADESK_DOMAIN)"
  if [ -n "${domain}" ]; then
    valid_domain "${domain}" || die "INFRADESK_DOMAIN must be a DNS hostname."
    extra=(--file "${dir}/compose.https.yml")
  fi
  docker compose --project-name "${INFRADESK_PROJECT}" --env-file "${INFRADESK_ENV_FILE}" \
    --file "${dir}/compose.yml" "${extra[@]}" "$@"
}
compose() { compose_in "${INFRADESK_APP_DIR}" "$@"; }

container_id() { compose ps --quiet "$1" 2>/dev/null | awk 'NR == 1'; }

container_health() {
  local id
  id="$(container_id "$1")"
  [ -n "${id}" ] || { printf 'absent'; return 0; }
  docker inspect --format '{{.State.Status}}{{if .State.Health}} / {{.State.Health.Status}}{{end}}' "${id}" 2>/dev/null || printf 'unknown'
}

wait_postgres_healthy() {
  local deadline=$((SECONDS + 120)) state
  while [ "${SECONDS}" -lt "${deadline}" ]; do
    state="$(container_health postgres)"
    [ "${state}" = "running / healthy" ] && return 0
    sleep 2
  done
  return 1
}

# --- http --------------------------------------------------------------------------------------

public_host() {
  local bind
  bind="$(state_value BIND_ADDRESS)"
  bind="${bind:-${1:-127.0.0.1}}"
  if [ "${bind}" = "0.0.0.0" ]; then printf '127.0.0.1'; else printf '%s' "${bind}"; fi
}

base_url() {
  local domain
  domain="$(env_value INFRADESK_DOMAIN)"
  if [ -n "${domain}" ]; then
    valid_domain "${domain}" || return 1
    printf 'https://%s' "${domain}"
  else
    printf 'http://%s:%s' "$(public_host "${1:-}")" "${2:-$(state_value HTTP_PORT)}"
  fi
}

http_status() {
  curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 "$1" 2>/dev/null || printf '000'
}

wait_ready() {
  local url="$1" timeout="${2:-${INFRADESK_READY_TIMEOUT_SECONDS}}" deadline
  deadline=$((SECONDS + timeout))
  while [ "${SECONDS}" -lt "${deadline}" ]; do
    [ "$(http_status "${url}/ready")" = "200" ] && return 0
    sleep 3
  done
  return 1
}

# The build identity the running backend reports through the public entrypoint.
running_identity() {
  local body
  body="$(curl --silent --max-time 10 "$1/health" 2>/dev/null || true)"
  printf '%s %s' "$(printf '%s' "${body}" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')" \
    "$(printf '%s' "${body}" | sed -n 's/.*"gitSha":"\([^"]*\)".*/\1/p')"
}

# Checks what a user reaches: the shell, a hashed asset, the API and the build identity.
smoke_test() {
  local url="$1" version="$2" sha="$3" asset identity
  [ "$(http_status "${url}/health")" = "200" ] || { warn "GET /health did not answer 200."; return 1; }
  [ "$(http_status "${url}/ready")" = "200" ] || { warn "GET /ready did not answer 200."; return 1; }
  [ "$(http_status "${url}/")" = "200" ] || { warn "The application shell did not answer 200."; return 1; }
  [ "$(http_status "${url}/organizations")" = "200" ] || { warn "A client-side route did not answer 200."; return 1; }
  asset="$(curl --silent --max-time 10 "${url}/" | sed -n 's/.*src="\([^"]*\/assets\/[^"]*\.js\)".*/\1/p' | awk 'NR == 1')"
  [ -n "${asset}" ] && [ "$(http_status "${url}${asset}")" = "200" ] || { warn "The frontend assets are not served."; return 1; }
  identity="$(running_identity "${url}")"
  if [ "${identity}" != "${version#v} ${sha}" ]; then
    warn "The running backend reports '${identity}', expected '${version#v} ${sha}'."
    return 1
  fi
}

# --- database ----------------------------------------------------------------------------------

db_schema_version() {
  compose exec -T postgres sh -c 'psql -tA --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --command \
    "select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1"' \
    2>/dev/null | tr -d '[:space:]'
}

# The newest successful migration recorded inside a custom-format dump, read without restoring it.
dump_schema_version() {
  compose exec -T postgres sh -c 'exec pg_restore --data-only --table=flyway_schema_history --file=-' < "$1" 2>/dev/null |
    awk -F '\t' '/^COPY / { copying = 1; next } /^\\\.$/ { copying = 0 } copying && $10 == "t" && $2 ~ /^[0-9]+$/ { if ($2 + 0 > max) max = $2 + 0 } END { if (max > 0) print max }'
}

dump_is_valid() {
  [ -s "$1" ] && compose exec -T postgres sh -c 'exec pg_restore --list' < "$1" >/dev/null 2>&1
}

# Takes a verified custom-format dump plus a metadata file. $1: name prefix. Prints the path.
backup_database() {
  local prefix="${1:-infradesk}" stamp target temporary schema
  install -d -m 700 -o root -g root "${INFRADESK_BACKUP_DIR}"
  [ -n "$(container_id postgres)" ] || die "PostgreSQL is not running, so no backup can be taken." \
    "Start InfraDesk with: infradesk start"
  stamp="$(date -u +%Y%m%d-%H%M%S)"
  target="${INFRADESK_BACKUP_DIR}/${prefix}-${stamp}.dump"
  temporary="$(mktemp "${INFRADESK_BACKUP_DIR}/.partial.XXXXXX")"
  chmod 600 "${temporary}"
  if ! compose exec -T postgres sh -c 'exec pg_dump --format=custom --no-owner --username "$POSTGRES_USER" "$POSTGRES_DB"' \
    > "${temporary}"; then
    rm -f "${temporary}"
    die "pg_dump failed; no backup was written."
  fi
  if ! dump_is_valid "${temporary}"; then
    rm -f "${temporary}"
    die "The dump could not be read back; no backup was written."
  fi
  schema="$(db_schema_version)"
  # Two backups in the same second must never overwrite each other: take the next free name, and
  # let the no-clobber rename fail rather than replace an existing dump.
  local attempt=1
  while [ -e "${target}" ] || [ -e "${target}.meta" ]; do
    attempt=$((attempt + 1))
    target="${INFRADESK_BACKUP_DIR}/${prefix}-${stamp}-${attempt}.dump"
  done
  mv -n "${temporary}" "${target}"
  if [ -e "${temporary}" ]; then
    rm -f "${temporary}"
    die "A backup named ${target} appeared concurrently; no backup was written. Try again."
  fi
  write_private_file "${target}.meta" "FORMAT=pg_dump-custom
VERSION=$(state_value VERSION)
GIT_SHA=$(state_value GIT_SHA)
SCHEMA_VERSION=${schema}
CREATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '%s' "${target}"
}

env_backup_warning() {
  log ""
  log "IMPORTANT:"
  log "Back up ${INFRADESK_ENV_FILE} separately and securely."
  log "Without the encryption key in it, encrypted connection credentials cannot be recovered."
}
