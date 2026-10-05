#!/bin/sh
set -eu
mkdir -m 0755 /etc/profile-tests
mkdir -m 0700 /tmp/infradesk-check
printf '# managed\nnew\n' >/tmp/infradesk-check/input
chmod 0600 /tmp/infradesk-check/input
new=$(sha256sum /tmp/infradesk-check/input | cut -d' ' -f1)
marker='# managed'
target=/etc/profile-tests/config
stage=/etc/profile-tests/.candidate
expect_failure() { want=$1; shift; set +e; "$@"; got=$?; set -e; [ "$got" = "$want" ] || { echo "expected $want got $got"; exit 1; }; }
prepare() { sh /checks/Prepare.sh "$target" /tmp/infradesk-check/input "$stage" 0 "$new" "$1" "$marker" false 0644; }
commit() { sh /checks/Commit.sh "$target" "$stage/candidate" "$stage/previous" "$1" "$new"; }
prepare MISSING
[ "$(stat -c '%u:%a:%h' "$stage/candidate")" = '0:644:1' ]
commit MISSING
[ "$(sha256sum "$target" | cut -d' ' -f1)" = "$new" ]
echo 'PASS exclusive private staging and first atomic commit'
rm -f "$target"; rmdir "$stage"
secret_target=/etc/profile-tests/.env
secret_stage=/etc/profile-tests/.env-stage
sh /checks/Prepare.sh "$secret_target" /tmp/infradesk-check/input "$secret_stage" 0 "$new" MISSING "$marker" true 0600
[ "$(stat -c '%u:%a:%h' "$secret_stage/candidate")" = '0:600:1' ]
sh /checks/Commit.sh "$secret_target" "$secret_stage/candidate" "$secret_stage/previous" MISSING "$new"
[ "$(stat -c '%u:%a:%h' "$secret_target")" = '0:600:1' ]
rm -f "$secret_target"; rmdir "$secret_stage"
echo 'PASS secret file is atomically installed with mode 0600'
printf '# managed\nold\n' >"$target"
old=$(sha256sum "$target" | cut -d' ' -f1)
prepare "$old"; commit "$old"
sh /checks/Rollback.sh "$target" "$stage/previous" "$old" "$new"
[ "$(sha256sum "$target" | cut -d' ' -f1)" = "$old" ]
rmdir "$stage"
echo 'PASS known predecessor rollback'
printf 'foreign\n' >"$target"
foreign=$(sha256sum "$target" | cut -d' ' -f1)
expect_failure 43 prepare "$foreign"
[ "$(cat "$target")" = foreign ]
echo 'PASS unmanaged target preserved'
rm -f "$target"; ln -s /etc/passwd "$target"
expect_failure 43 prepare MISSING
rm -f "$target"
printf '# managed\nold\n' >"$target"; chmod 0666 "$target"
expect_failure 43 prepare "$old"
chmod 0644 "$target"; ln "$target" /etc/profile-tests/link
expect_failure 43 prepare "$old"
rm /etc/profile-tests/link
prepare "$old"; printf '# managed\nexternal\n' >"$target"
expect_failure 43 commit "$old"
[ "$(cat "$target")" = "$(printf '# managed\nexternal')" ]
echo 'PASS symlink, writable, hardlink and concurrent edit guards'
rm "$stage/candidate"; rmdir "$stage"
printf '# managed\nold\n' >"$target"; prepare "$old"; commit "$old"
printf '# managed\nexternal\n' >"$target"
expect_failure 43 sh /checks/Rollback.sh "$target" "$stage/previous" "$old" "$new"
echo 'PASS rollback refuses concurrent replacement'
probe=$(sh /checks/FileProbe.sh /etc/profile-tests/not-created/file "$marker" false)
[ "$probe" = MISSING ] && [ ! -e /etc/profile-tests/not-created ]
chmod 0666 "$target"
[ "$(sh /checks/FileProbe.sh "$target" "$marker" false)" = UNSAFE ]
echo 'PASS observation is read-only and rejects writable files'
mkdir /mocks
cat >/mocks/apt-get <<'MOCK'
#!/bin/sh
printf '%s\n' "$SIMULATION"
exit "${APT_EXIT:-0}"
MOCK
cat >/mocks/dpkg-query <<'MOCK'
#!/bin/sh
printf '%s' "${PACKAGE_STATUS:-}"
MOCK
chmod 0755 /mocks/apt-get /mocks/dpkg-query
PATH=/mocks:$PATH; export PATH
SIMULATION='Inst curl (1 repository [amd64])'; export SIMULATION
PACKAGE_STATUS=''; export PACKAGE_STATUS
sh /checks/Simulate.sh curl
echo 'PASS install simulation accepts only absent packages'
PACKAGE_STATUS='ii '; export PACKAGE_STATUS
expect_failure 54 sh /checks/Simulate.sh curl
PACKAGE_STATUS='rc '; export PACKAGE_STATUS
expect_failure 54 sh /checks/Simulate.sh curl
SIMULATION='Remv curl [1]'; export SIMULATION
expect_failure 54 sh /checks/Simulate.sh curl
APT_EXIT=1; export APT_EXIT
expect_failure 53 sh /checks/Simulate.sh curl
echo 'PASS simulation rejects upgrades, broken packages, removals and apt failure'
# Exercise the actual extracted Stage25C proof and start helpers in a disposable Linux container.
mkdir -p /opt/infradesk/remnawave
node_dir=/opt/infradesk/remnawave/11111111-1111-1111-1111-111111111111
mkdir -m 0755 "$node_dir"
node_marker='# infradesk-managed test'
printf '%s\nservices: {}\n' "$node_marker" >"$node_dir/compose.yml"
printf 'SECRET_KEY=c2VjcmV0\nNODE_PORT=2222\n' >"$node_dir/.env"
chmod 0600 "$node_dir/.env"
env_hash=$(sha256sum "$node_dir/.env" | cut -d' ' -f1)
compose_hash=$(sha256sum "$node_dir/compose.yml" | cut -d' ' -f1)
prefix='{"managedBy":"infradesk","envSha256":"'
printf '%s%s"}\n' "$prefix" "$env_hash" >"$node_dir/managed.json"
cat >/mocks/docker <<'MOCK'
#!/bin/sh
if [ "$1" = image ]; then printf sha256:mock; exit 0; fi
if [ "$1" = compose ]; then
  case "$*" in *'up -d'*) touch /tmp/node-started;; esac
  exit 0
fi
exit 0
MOCK
cat >/mocks/ss <<'MOCK'
#!/bin/sh
exit "${SS_EXIT:-0}"
MOCK
chmod +x /mocks/docker /mocks/ss
export PATH=/mocks:$PATH
node_proof() { sh /checks/NodeInstallationProof.sh "$node_dir" "$node_marker" remnawave/node:2.8.0 2222 "$compose_hash" "$prefix"; }
node_start() { sh /checks/NodeStart.sh "$node_dir" "$node_marker" remnawave/node:2.8.0 2222 "$compose_hash" "$prefix" test-node; }
[ "$(node_proof)" = '1:1' ]
[ "$(node_start)" = STARTED ] && [ -e /tmp/node-started ]
rm /tmp/node-started
printf 'SECRET_KEY=c2VjcmV0\nNODE_PORT=2223\n' >"$node_dir/.env"
[ "$(node_proof)" = '0:1' ] && [ "$(node_start)" = FILES ] && [ ! -e /tmp/node-started ]
printf 'SECRET_KEY=c2VjcmV0\nNODE_PORT=2222\n' >"$node_dir/.env"
chmod 0644 "$node_dir/.env"
[ "$(node_proof)" = '0:1' ]
chmod 0600 "$node_dir/.env"; ln "$node_dir/.env" "$node_dir/env-link"
[ "$(node_proof)" = '0:1' ]
rm "$node_dir/env-link"; mv "$node_dir/.env" "$node_dir/env-save"; ln -s "$node_dir/env-save" "$node_dir/.env"
[ "$(node_proof)" = '0:1' ] && [ "$(node_start)" = FILES ]
[ "$(SS_EXIT=1 sh /checks/NodePreflight.sh 2222 1)" = UNKNOWN ]
[ "$(sh /checks/NodePreflight.sh 2222 1)" = FOREIGN ]
echo 'PASS node install recovery and start reject credential drift, public modes, hardlinks, symlinks, and failed port probes'
# The software lifecycle may replace exactly one reviewed image line and no other owned byte.
rm "$node_dir/.env"; mv "$node_dir/env-save" "$node_dir/.env"; chmod 0600 "$node_dir/.env"
original_image=$(sed -n '1p' /checks/NodeImageRefs.txt)
target_image=$(sed -n '2p' /checks/NodeImageRefs.txt)
node_marker="# infradesk-managed test image=$original_image"
printf '%s\nservices:\n  node:\n    image: %s\n    network_mode: host\n' "$node_marker" "$original_image" >"$node_dir/compose.yml"
compose_hash=$(sha256sum "$node_dir/compose.yml" | cut -d' ' -f1)
image_owned() { sh /checks/NodeImageOwnership.sh "$node_dir" "$node_marker" 2222 "$compose_hash" "$prefix"; }
[ "$(image_owned)" = OWNED ]
sed -i "s|^    image: .*|    image: $target_image|" "$node_dir/compose.yml"
[ "$(image_owned)" = OWNED ]
sed -i 's|network_mode: host|network_mode: bridge|' "$node_dir/compose.yml"
[ "$(image_owned)" = UNMANAGED ]
sed -i 's|network_mode: bridge|network_mode: host|' "$node_dir/compose.yml"
printf '    image: %s\n' "$target_image" >>"$node_dir/compose.yml"
[ "$(image_owned)" = UNMANAGED ]
sed -i '$d' "$node_dir/compose.yml"
sed -i 's|^    image: .*|    image: ghcr.io/evil/node:latest|' "$node_dir/compose.yml"
[ "$(image_owned)" = UNMANAGED ]
echo 'PASS controlled image ownership accepts reviewed upgrades and rejects other edits, duplicate images, and foreign references'
# Recovery preflight may accept an exact damaged own install but must reject foreign
# ownership evidence and unexpected hidden entries without changing any host files.
recovery_run=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
recovery_resource=bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb
recovery_node=cccccccc-cccc-cccc-cccc-cccccccccccc
recovery_image=remnawave/node:2.8.0
recovery_port=32222
recovery_name=infradesk-remnawave-$recovery_node
recovery_dir=/opt/infradesk/remnawave/$recovery_node
recovery_marker="# infradesk-managed run=$recovery_run node=$recovery_node resource=$recovery_resource image=$recovery_image"
recovery_prefix="{\"managedBy\":\"infradesk\",\"runId\":\"$recovery_run\",\"nodeId\":\"$recovery_node\",\"resourceId\":\"$recovery_resource\",\"image\":\"$recovery_image\",\"envSha256\":\""
mkdir -m 0755 "$recovery_dir"
printf '%s\nservices: {}\n' "$recovery_marker" >"$recovery_dir/compose.yml"
printf 'damaged but private\n' >"$recovery_dir/.env"; chmod 0600 "$recovery_dir/.env"
node_recovery() { sh /checks/NodeRecoveryProbe.sh "$recovery_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port"; }
[ "$(node_recovery)" = OWNED ]
foreign_dir=/opt/infradesk/remnawave/dddddddd-dddd-dddd-dddd-dddddddddddd
mkdir -m 0755 "$foreign_dir"
printf 'foreign\n' >"$foreign_dir/compose.yml"
[ "$(sh /checks/NodeRecoveryProbe.sh "$foreign_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port")" = FOREIGN ]
printf 'hidden foreign data\n' >"$recovery_dir/..foreign"
[ "$(node_recovery)" = FOREIGN ] && [ "$(cat "$recovery_dir/..foreign")" = 'hidden foreign data' ]
rm -f "$recovery_dir/..foreign"
rm -f "$recovery_dir/compose.yml" "$recovery_dir/.env" "$foreign_dir/compose.yml"; rmdir "$recovery_dir" "$foreign_dir"
echo 'PASS recovery preflight recognizes damaged exact ownership and rejects foreign or hidden entries'

# Retirement is limited to the exact owned files and container. Stop/rm failures
# leave all files in place, while a successful retirement is idempotent.
retire_dir=/opt/infradesk/remnawave/$recovery_node
mkdir -m 0755 "$retire_dir"
cat >"$retire_dir/compose.yml" <<EOF
$recovery_marker
services:
  node:
    image: $recovery_image
    container_name: $recovery_name
    network_mode: host
    cap_add: ["NET_ADMIN"]
    ulimits:
      nofile:
        soft: 1048576
        hard: 1048576
    env_file: .env
    restart: unless-stopped
EOF
printf 'SECRET_KEY=c2VjcmV0\nNODE_PORT=%s\n' "$recovery_port" >"$retire_dir/.env"
chmod 0600 "$retire_dir/.env"
retire_env_hash=$(sha256sum "$retire_dir/.env" | cut -d' ' -f1)
printf '%s%s"}\n' "$recovery_prefix" "$retire_env_hash" >"$retire_dir/managed.json"
compose_hash=$(sha256sum "$retire_dir/compose.yml" | cut -d' ' -f1)
cat >/mocks/docker <<'MOCK'
#!/bin/sh
case "$1" in
  ps) [ -e /tmp/remnawave-retire-removed ] || printf '%s' "${DOCKER_CONTAINERS:-}" ;;
  inspect) printf '%s' "${DOCKER_INSPECT_INFO:-}" ;;
  stop) [ "${DOCKER_STOP_EXIT:-0}" = 0 ] || exit "$DOCKER_STOP_EXIT" ;;
  rm) [ "${DOCKER_RM_EXIT:-0}" = 0 ] || exit "$DOCKER_RM_EXIT"; touch /tmp/remnawave-retire-removed ;;
  *) exit 0 ;;
esac
MOCK
chmod 0755 /mocks/docker
export DOCKER_CONTAINERS="$recovery_name"
export DOCKER_INSPECT_INFO="$retire_dir/compose.yml|$recovery_image|true"
node_retire() { sh /checks/NodeRetireInstallation.sh "$retire_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port"; }
rm -f /tmp/remnawave-retire-removed
[ "$(DOCKER_STOP_EXIT=9 node_retire)" = UNCERTAIN ]
[ -f "$retire_dir/.env" ] && [ ! -e /tmp/remnawave-retire-removed ]
[ "$(DOCKER_RM_EXIT=9 node_retire)" = UNCERTAIN ]
[ -f "$retire_dir/.env" ] && [ ! -e /tmp/remnawave-retire-removed ]
# Owned damaged installation remains safely retireable after explicit recreate approval.
printf 'damaged private credential\n' >"$retire_dir/.env"
[ "$(node_retire)" = RETIRED ]
[ ! -e "$retire_dir" ] && [ -e /tmp/remnawave-retire-removed ]
export DOCKER_CONTAINERS=''
[ "$(node_retire)" = ABSENT ]
rm -f /tmp/remnawave-retire-removed
echo 'PASS retirement stops/removes only an exact owned container and preserves files on uncertain failures'
echo 'LINUX SAFETY CHECKS PASSED'
