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
# Crash recovery is exercised through the exact extracted production shell, including
# atomic owner claims, ProfileManagedFiles candidate/commit behavior and retirement.
cat >/mocks/ss <<'MOCK'
#!/bin/sh
[ "${SS_EXIT:-0}" = 0 ] || exit "$SS_EXIT"
printf '%s' "${SS_LISTENERS:-}"
MOCK
chmod 0755 /mocks/ss
recovery_image=remnawave/node:2.8.0
recovery_port=32222
image_hash=$(printf '%s' "$recovery_image" | sha256sum | cut -d' ' -f1)
set_recovery_identity() {
  recovery_run=$1; recovery_resource=$2; recovery_node=$3
  recovery_name=infradesk-remnawave-$recovery_node
  recovery_dir=/opt/infradesk/remnawave/$recovery_node
  recovery_claim=/opt/infradesk/remnawave/.owner-$recovery_node-$recovery_run-$recovery_resource-$image_hash
  recovery_stage=/opt/infradesk/remnawave/.staging-$recovery_node-$recovery_run-$recovery_resource-$image_hash
  recovery_marker="# infradesk-managed run=$recovery_run node=$recovery_node resource=$recovery_resource image=$recovery_image"
  recovery_prefix="{\"managedBy\":\"infradesk\",\"runId\":\"$recovery_run\",\"nodeId\":\"$recovery_node\",\"resourceId\":\"$recovery_resource\",\"image\":\"$recovery_image\",\"envSha256\":\""
  cat >"$recovery_dir.compose" <<EOF
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
  recovery_compose_hash=$(sha256sum "$recovery_dir.compose" | cut -d' ' -f1)
}
recovery_args() { printf '%s\n' "$recovery_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port" "$recovery_claim" "$recovery_compose_hash"; }
run_recovery_script() { script=$1; shift; sh "/checks/Node$script.sh" "$@"; }
node_recovery() { run_recovery_script RecoveryProbe "$recovery_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port" "$recovery_claim" "$recovery_compose_hash"; }
node_recovery_uid() { run_recovery_script RecoveryProbe "$recovery_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port" "$recovery_claim" "$recovery_compose_hash" "$1"; }
node_prepare() { run_recovery_script PrepareInstallation "$recovery_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port" "$recovery_claim" "$recovery_compose_hash"; }
node_publish() { run_recovery_script PublishInstallation "$recovery_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port" "$recovery_claim" "$recovery_compose_hash"; }
node_cleanup() { run_recovery_script CleanupInstallationStaging "$recovery_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port" "$recovery_claim" "$recovery_compose_hash"; }
node_cleanup_uid() { run_recovery_script CleanupInstallationStaging "$recovery_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port" "$recovery_claim" "$recovery_compose_hash" "$1"; }
node_retire() { run_recovery_script RetireInstallation "$recovery_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port" "$recovery_claim" "$recovery_compose_hash"; }
write_managed_file() {
  target=$1; bytes=$2; mode=$3; nonce=$4
  userdir=/tmp/infradesk-$recovery_run-$nonce
  parent=${target%/*}
  stage=$parent/.infradesk-$recovery_run-$nonce
  mkdir -m 0700 "$userdir"
  printf '%s' "$bytes" >"$userdir/input"; chmod 0600 "$userdir/input"
  hash=$(sha256sum "$userdir/input" | cut -d' ' -f1)
  sh /checks/Prepare.sh "$target" "$userdir/input" "$stage" 0 "$hash" MISSING "$recovery_marker" true "$mode"
  sh /checks/Commit.sh "$target" "$stage/candidate" "$stage/previous" MISSING "$hash"
  rmdir "$stage"; rm "$userdir/input"; rmdir "$userdir"
}
write_env() { bytes=$(printf 'SECRET_KEY=ZHVtbXk=\nNODE_PORT=%s\n' "$recovery_port"); bytes="$bytes
"; write_managed_file "$recovery_stage/.env" "$bytes" 0600 11111111-1111-1111-1111-111111111111; }
write_compose() { bytes=$(cat "$recovery_dir.compose"); bytes="$bytes
"; write_managed_file "$recovery_stage/compose.yml" "$bytes" 0644 22222222-2222-2222-2222-222222222222; }
write_metadata() { envhash=$(sha256sum "$recovery_stage/.env" | cut -d' ' -f1); bytes=$(printf '%s%s"}\n' "$recovery_prefix" "$envhash"); write_managed_file "$recovery_stage/managed.json" "$bytes" 0644 33333333-3333-3333-3333-333333333333; }
cleanup_fixture() {
  for f in "$recovery_dir"/.infradesk-*; do [ -e "$f" ] || [ -L "$f" ] || continue; rm -f -- "$f/candidate" "$f/previous"; rmdir -- "$f"; done
  for f in "$recovery_stage"/.infradesk-*; do [ -e "$f" ] || [ -L "$f" ] || continue; rm -f -- "$f/candidate" "$f/previous"; rmdir -- "$f"; done
  for f in "$recovery_dir/compose.yml" "$recovery_dir/.env" "$recovery_dir/managed.json"; do [ ! -e "$f" ] && [ ! -L "$f" ] || rm -- "$f"; done
  for f in "$recovery_stage/compose.yml" "$recovery_stage/.env" "$recovery_stage/managed.json"; do [ ! -e "$f" ] && [ ! -L "$f" ] || rm -- "$f"; done
  [ ! -d "$recovery_dir" ] || rmdir "$recovery_dir"
  [ ! -d "$recovery_stage" ] || rmdir "$recovery_stage"
  [ ! -d "$recovery_claim" ] || rmdir "$recovery_claim"
  rm -f "$recovery_dir.compose"
}

# A crash immediately after atomic claim publication is recoverable as owned partial.
set_recovery_identity aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb cccccccc-cccc-cccc-cccc-cccccccccccc
cat >/mocks/mkdir <<'MOCK'
#!/bin/sh
for arg do
  [ "$arg" = "$TRACE_CLAIM" ] && printf claim >>"$TRACE_LOG"
  if [ "$arg" = "$TRACE_NODE" ]; then printf node >>"$TRACE_LOG"; exit 88; fi
done
exec /bin/mkdir "$@"
MOCK
chmod 0755 /mocks/mkdir
export TRACE_CLAIM="$recovery_claim" TRACE_NODE="$recovery_stage" TRACE_LOG=/tmp/remnawave-bootstrap-order
rm -f "$TRACE_LOG"
[ "$(node_prepare)" = UNKNOWN ]
[ "$(cat "$TRACE_LOG")" = claimnode ] && [ -d "$recovery_claim" ] && [ ! -e "$recovery_dir" ] || exit 1
rm /mocks/mkdir "$TRACE_LOG"; unset TRACE_CLAIM TRACE_NODE TRACE_LOG
[ "$(stat -c '%u:%g:%a' "$recovery_claim")" = 0:0:700 ]
[ "$(node_recovery)" = OWNED_PARTIAL ]
[ "$(node_prepare)" = PREPARED ] && [ "$(node_recovery)" = OWNED_PARTIAL ] || exit 1
claim_inode=$(stat -c '%i' "$recovery_claim"); node_inode=$(stat -c '%i' "$recovery_stage")
[ "$(node_prepare)" = PREPARED ]
[ "$(stat -c '%i' "$recovery_claim")" = "$claim_inode" ] && [ "$(stat -c '%i' "$recovery_stage")" = "$node_inode" ] && [ ! -e "$recovery_dir" ] || exit 1
echo 'PASS atomic ownership claim precedes deterministic staging and repeated preparation preserves inode identity'
cleanup_fixture

# Every pre-publication crash boundary leaves final absent; publish changes it atomically to complete.
phase=1
cat >/mocks/docker <<'MOCK'
#!/bin/sh
case "$1" in
  compose) [ "$2" = -f ] && [ -f "$3" ] && [ "$6" = config ] || exit 1; exit "${DOCKER_CONFIG_EXIT:-0}" ;;
  image) printf image-id ;;
  ps) printf '' ;;
  *) exit 0 ;;
esac
MOCK
chmod 0755 /mocks/docker
for crash_after in claim stage env compose metadata; do
  run=$(printf '%08x-aaaa-aaaa-aaaa-%012x' "$phase" "$phase")
  resource=$(printf '%08x-bbbb-bbbb-bbbb-%012x' "$phase" "$phase")
  node=$(printf '%08x-cccc-cccc-cccc-%012x' "$phase" "$phase")
  set_recovery_identity "$run" "$resource" "$node"
  if [ "$crash_after" = claim ]; then mkdir -m 0700 "$recovery_claim"; else [ "$(node_prepare)" = PREPARED ]; fi
  [ "$(node_recovery)" = OWNED_PARTIAL ]
  case "$crash_after" in env) write_env ;; compose) write_env; write_compose ;; metadata) write_env; write_compose; write_metadata ;; esac
  [ "$(node_recovery)" = OWNED_PARTIAL ]
  [ ! -e "$recovery_dir" ] || exit 1
  [ "$(node_prepare)" = PREPARED ]
  [ -f "$recovery_stage/.env" ] || write_env
  [ -f "$recovery_stage/compose.yml" ] || write_compose
  [ -f "$recovery_stage/managed.json" ] || write_metadata
  [ "$(node_recovery)" = OWNED_PARTIAL ]
  stage_inode=$(stat -c '%i' "$recovery_stage")
  [ "$(node_publish)" = PUBLISHED ]
  [ "$(stat -c '%i' "$recovery_dir")" = "$stage_inode" ] && [ ! -e "$recovery_stage" ] || exit 1
  [ "$(node_recovery)" = OWNED_COMPLETE ]
  before_retry=$(stat -c '%i:%s:%Y' "$recovery_dir/.env" "$recovery_dir/compose.yml" "$recovery_dir/managed.json")
  [ "$(node_publish)" = FOREIGN ]
  [ "$(node_cleanup)" = CLEAN ]
  [ "$(node_recovery)" = OWNED_COMPLETE ]
  [ "$(stat -c '%i:%s:%Y' "$recovery_dir/.env" "$recovery_dir/compose.yml" "$recovery_dir/managed.json")" = "$before_retry" ]
  phase=$((phase + 1)); cleanup_fixture
done
echo 'PASS claim, staging, env, compose, metadata and publish crash boundaries preserve atomic final visibility'

# Validation failure and destination races leave the complete stage and foreign destination untouched.
set_recovery_identity 12121212-aaaa-aaaa-aaaa-121212121212 23232323-bbbb-bbbb-bbbb-232323232323 34343434-cccc-cccc-cccc-343434343434
[ "$(node_prepare)" = PREPARED ]; write_env; write_compose; write_metadata
export DOCKER_CONFIG_EXIT=1
[ "$(node_publish)" = FOREIGN ] && [ ! -e "$recovery_dir" ] && [ -f "$recovery_stage/.env" ] || exit 1
unset DOCKER_CONFIG_EXIT
mkdir -m 0755 "$recovery_dir"; printf foreign >"$recovery_dir/foreign"
[ "$(node_publish)" = FOREIGN ] && [ "$(cat "$recovery_dir/foreign")" = foreign ] && [ -f "$recovery_stage/.env" ] || exit 1
echo 'PASS compose validation and destination races preserve staged data and never overwrite an existing destination'
rm "$recovery_dir/foreign"; rmdir "$recovery_dir"
cleanup_fixture

# Arbitrary env-only data cannot claim ownership; mismatched and unsafe identities stay untouched.
set_recovery_identity dddddddd-aaaa-aaaa-aaaa-dddddddddddd eeeeeeee-bbbb-bbbb-bbbb-eeeeeeeeeeee ffffffff-cccc-cccc-cccc-ffffffffffff
mkdir -m 0755 "$recovery_dir"; printf 'SECRET_KEY=ZHVtbXk=\nNODE_PORT=%s\n' "$recovery_port" >"$recovery_dir/.env"; chmod 0600 "$recovery_dir/.env"
[ "$(node_recovery)" = FOREIGN ] && [ "$(node_prepare)" = FOREIGN ] || exit 1
rm "$recovery_dir/.env"; rmdir "$recovery_dir"
mkdir -m 0700 "$recovery_claim"
wrong_claim=${recovery_claim}-wrong
[ "$(run_recovery_script RecoveryProbe "$recovery_dir" "$recovery_marker" "$recovery_prefix" "$recovery_name" "$recovery_image" "$recovery_port" "$wrong_claim" "$recovery_compose_hash")" = FOREIGN ]
chmod 0755 "$recovery_claim"; [ "$(node_recovery)" = FOREIGN ]; chmod 0700 "$recovery_claim"
touch "$recovery_claim/foreign"; [ "$(node_recovery)" = FOREIGN ]; rm "$recovery_claim/foreign"
chown 65534:65534 "$recovery_claim"; [ "$(node_recovery)" = FOREIGN ]; chown 0:0 "$recovery_claim"
chown 0:65534 "$recovery_claim"; [ "$(node_recovery)" = FOREIGN ]; chown 0:0 "$recovery_claim"
[ "$(node_recovery)" = OWNED_PARTIAL ]
rmdir "$recovery_claim"
mkdir -m 0700 "$recovery_claim-target"
ln -s "$recovery_claim-target" "$recovery_claim"
[ "$(node_recovery)" = FOREIGN ] && [ -d "$recovery_claim-target" ] || exit 1
rm "$recovery_claim"; rmdir "$recovery_claim-target"
echo 'PASS arbitrary env-only and mismatched, nonempty, wrong-mode, or wrong-owner claims fail closed'

# A staging name is not an ownership claim; only the exact stage matching the durable owner may exist.
set_recovery_identity 45454545-aaaa-aaaa-aaaa-454545454545 56565656-bbbb-bbbb-bbbb-565656565656 67676767-cccc-cccc-cccc-676767676767
mkdir -m 0755 "$recovery_stage"
printf '%s\n' "$recovery_marker" >"$recovery_stage/compose.yml"; chmod 0644 "$recovery_stage/compose.yml"
[ "$(node_recovery)" = FOREIGN ] && [ -f "$recovery_stage/compose.yml" ] || exit 1
rm "$recovery_stage/compose.yml"; rmdir "$recovery_stage"
[ "$(node_prepare)" = PREPARED ]
conflicting_stage="/opt/infradesk/remnawave/.staging-$recovery_node-other"
mkdir -m 0755 "$conflicting_stage"
[ "$(node_recovery)" = FOREIGN ] && [ -d "$conflicting_stage" ] || exit 1
rmdir "$conflicting_stage"
[ "$(node_recovery)" = OWNED_PARTIAL ]
echo 'PASS forged staging without a claim and conflicting sibling staging fail closed'
cleanup_fixture

# Complete proof is accepted only with exact hashes, shape and safe file metadata.
set_recovery_identity 11111111-aaaa-aaaa-aaaa-111111111111 22222222-bbbb-bbbb-bbbb-222222222222 33333333-cccc-cccc-cccc-333333333333
[ "$(node_prepare)" = PREPARED ]; write_env; write_compose; write_metadata
[ "$(node_recovery)" = OWNED_PARTIAL ] && [ ! -e "$recovery_dir" ]
[ "$(node_publish)" = PUBLISHED ] && [ "$(node_recovery)" = OWNED_COMPLETE ]
claim_inode=$(stat -c '%i' "$recovery_claim"); node_inode=$(stat -c '%i' "$recovery_dir")
stage=$recovery_dir/.infradesk-$recovery_run-44444444-4444-4444-4444-444444444444
userdir=/tmp/infradesk-$recovery_run-44444444-4444-4444-4444-444444444444
mkdir -m 0700 "$userdir"; cp "$recovery_dir/.env" "$userdir/input"; chmod 0600 "$userdir/input"
stage_hash=$(sha256sum "$userdir/input" | cut -d' ' -f1); env_hash=$(sha256sum "$recovery_dir/.env" | cut -d' ' -f1)
sh /checks/Prepare.sh "$recovery_dir/.env" "$userdir/input" "$stage" 0 "$stage_hash" "$env_hash" "$recovery_marker" true 0600
rm "$userdir/input"; rmdir "$userdir"
[ "$(node_recovery)" = OWNED_PARTIAL ]
[ "$(node_cleanup)" = CLEAN ] && [ "$(node_recovery)" = OWNED_COMPLETE ] || exit 1
[ "$(node_cleanup)" = CLEAN ]
[ "$(stat -c '%i' "$recovery_claim")" = "$claim_inode" ] && [ "$(stat -c '%i' "$recovery_dir")" = "$node_inode" ] || exit 1
tmpnonce=66666666-6666-6666-6666-666666666666
tmpupload=/tmp/infradesk-$recovery_run-$tmpnonce
mkdir -m 0700 "$tmpupload"; printf 'private input\n' >"$tmpupload/input"; chmod 0600 "$tmpupload/input"
[ "$(node_recovery)" = OWNED_PARTIAL ]
[ "$(node_cleanup)" = CLEAN ] && [ ! -e "$tmpupload" ] && [ "$(node_recovery)" = OWNED_COMPLETE ] || exit 1
mkdir -m 0700 "$tmpupload"; printf 'private input\n' >"$tmpupload/input"; chmod 0600 "$tmpupload/input"; chown 501:501 "$tmpupload" "$tmpupload/input"
[ "$(node_recovery_uid 501)" = OWNED_PARTIAL ]
[ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] && [ -f "$tmpupload/input" ] || exit 1
[ "$(node_cleanup_uid 501)" = CLEAN ] && [ ! -e "$tmpupload" ] && [ "$(node_recovery)" = OWNED_COMPLETE ] || exit 1
mkdir -m 0700 "$tmpupload"; printf 'private input\n' >"$tmpupload/input"; chmod 0600 "$tmpupload/input"
chmod 0755 "$tmpupload"; [ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] && [ -f "$tmpupload/input" ] || exit 1; chmod 0700 "$tmpupload"
chown 65534:65534 "$tmpupload"; [ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] || exit 1; chown 0:0 "$tmpupload"
chown 0:65534 "$tmpupload"; [ "$(node_recovery)" = OWNED_PARTIAL ]; chown 0:0 "$tmpupload"
touch "$tmpupload/extra"; [ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] || exit 1; rm "$tmpupload/extra"
mv "$tmpupload/input" "$tmpupload/saved"; ln -s "$tmpupload/saved" "$tmpupload/input"
[ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] || exit 1; rm "$tmpupload/input" "$tmpupload/saved"; rmdir "$tmpupload"
echo 'PASS actual ProfileManagedFiles commit, root stages and SSH upload leftovers recover safely without exposing file contents'
printf 'SECRET_KEY=ZHVtbXk=\nNODE_PORT=%s\n' "$((recovery_port + 1))" >"$recovery_dir/.env"; chmod 0600 "$recovery_dir/.env"
[ "$(node_recovery)" = OWNED_DAMAGED ]
printf 'SECRET_KEY=ZHVtbXk=\nNODE_PORT=%s\n' "$recovery_port" >"$recovery_dir/.env"; chmod 0600 "$recovery_dir/.env"
[ "$(node_recovery)" = OWNED_COMPLETE ]

[ "$(SS_EXIT=9 node_recovery)" = UNKNOWN ]
[ "$(SS_LISTENERS=LISTEN node_recovery)" = PORT_CONFLICT ]
[ "$(SS_LISTENERS=LISTEN node_prepare)" = PORT_CONFLICT ]
[ -f "$recovery_dir/.env" ]
chmod 2755 /opt/infradesk/remnawave
[ "$(node_recovery)" = FOREIGN ]
[ "$(node_cleanup)" = FOREIGN ]
chmod g-s /opt/infradesk/remnawave
chmod 0755 /opt/infradesk/remnawave
[ "$(node_recovery)" = OWNED_COMPLETE ]
echo 'PASS unknown listener observations, port conflicts and inherited special modes fail closed'

printf 'unsafe\n' >"$recovery_dir/..foreign"
[ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] && [ -f "$recovery_dir/..foreign" ] || exit 1
rm "$recovery_dir/..foreign"
chmod 0644 "$recovery_dir/.env"; [ "$(node_recovery)" = FOREIGN ]; chmod 0600 "$recovery_dir/.env"
ln "$recovery_dir/.env" "$recovery_dir/env-hardlink"; [ "$(node_recovery)" = FOREIGN ]; rm "$recovery_dir/env-hardlink"
mv "$recovery_dir/.env" "$recovery_dir/env-save"; ln -s "$recovery_dir/env-save" "$recovery_dir/.env"
[ "$(node_recovery)" = FOREIGN ]; rm "$recovery_dir/.env"; mv "$recovery_dir/env-save" "$recovery_dir/.env"
chown 65534:65534 "$recovery_dir/.env"; [ "$(node_recovery)" = FOREIGN ]; chown 0:0 "$recovery_dir/.env"
chown 0:65534 "$recovery_dir/.env"; [ "$(node_recovery)" = FOREIGN ]; chown 0:0 "$recovery_dir/.env"
chmod 0700 "$recovery_dir"; [ "$(node_recovery)" = FOREIGN ]; chmod 0755 "$recovery_dir"
unsafe_stage=$recovery_dir/.infradesk-$recovery_run-55555555-5555-5555-5555-555555555555
mkdir -m 0700 "$unsafe_stage"; touch "$unsafe_stage/unexpected"
[ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] && [ -f "$unsafe_stage/unexpected" ] || exit 1
rm "$unsafe_stage/unexpected"; rmdir "$unsafe_stage"
mkdir -m 0700 "$unsafe_stage"; printf candidate >"$unsafe_stage/candidate"; chmod 0666 "$unsafe_stage/candidate"
[ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] || exit 1; chmod 0600 "$unsafe_stage/candidate"; rm "$unsafe_stage/candidate"; rmdir "$unsafe_stage"
mkdir -m 0700 "$unsafe_stage"; printf candidate >"$unsafe_stage/candidate"; chown 65534:65534 "$unsafe_stage/candidate"
[ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] || exit 1; rm "$unsafe_stage/candidate"; rmdir "$unsafe_stage"
mkdir -m 0700 "$unsafe_stage"; printf candidate >"$unsafe_stage/candidate"; chown 0:65534 "$unsafe_stage/candidate"
[ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] || exit 1; rm "$unsafe_stage/candidate"; rmdir "$unsafe_stage"
mkdir -m 0700 "$unsafe_stage"; printf candidate >"$unsafe_stage/candidate"; chmod 0600 "$unsafe_stage/candidate"; ln "$unsafe_stage/candidate" "$unsafe_stage/other"
[ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] || exit 1; rm "$unsafe_stage/other" "$unsafe_stage/candidate"; rmdir "$unsafe_stage"
mkdir -m 0700 "$unsafe_stage"; printf candidate >"$unsafe_stage/save"; chmod 0600 "$unsafe_stage/save"; ln -s "$unsafe_stage/save" "$unsafe_stage/candidate"
[ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] || exit 1; rm "$unsafe_stage/candidate" "$unsafe_stage/save"; rmdir "$unsafe_stage"
mkdir "$recovery_dir/foreign-directory"; printf keep >"$recovery_dir/foreign-directory/data"
[ "$(node_recovery)" = FOREIGN ] && [ "$(node_cleanup)" = FOREIGN ] && [ -f "$recovery_dir/foreign-directory/data" ] || exit 1
rm "$recovery_dir/foreign-directory/data"; rmdir "$recovery_dir/foreign-directory"
echo 'PASS symlinks, wrong UID/GID/mode, hardlinks, extra files and unsafe private stages remain foreign'

# Retirement failure cannot remove files; partial and marker-only states retire exactly and idempotently.
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
export DOCKER_CONTAINERS="$recovery_name" DOCKER_INSPECT_INFO="$recovery_dir/compose.yml|$recovery_image|true"
rm -f /tmp/remnawave-retire-removed
[ "$(DOCKER_STOP_EXIT=9 node_retire)" = UNCERTAIN ] && [ -f "$recovery_dir/.env" ] && [ -d "$recovery_claim" ] || exit 1
[ "$(DOCKER_RM_EXIT=9 node_retire)" = UNCERTAIN ] && [ -f "$recovery_dir/.env" ] && [ -d "$recovery_claim" ] || exit 1
printf 'damaged owned credential\n' >"$recovery_dir/.env"
[ "$(node_retire)" = RETIRED ] && [ ! -e "$recovery_dir" ] && [ ! -e "$recovery_claim" ] || exit 1
export DOCKER_CONTAINERS=''
[ "$(node_retire)" = ABSENT ]
rm -f /tmp/remnawave-retire-removed
set_recovery_identity 66666666-aaaa-aaaa-aaaa-666666666666 77777777-bbbb-bbbb-bbbb-777777777777 88888888-cccc-cccc-cccc-888888888888
mkdir -m 0700 "$recovery_claim"
[ "$(node_recovery)" = OWNED_PARTIAL ] && [ "$(node_retire)" = RETIRED ] && [ ! -e "$recovery_claim" ] || exit 1
set_recovery_identity 99999999-aaaa-aaaa-aaaa-999999999999 aaaaaaaa-bbbb-bbbb-bbbb-aaaaaaaaaaaa bbbbbbbb-cccc-cccc-cccc-bbbbbbbbbbbb
[ "$(node_prepare)" = PREPARED ]; printf '%s\n' "$recovery_marker" >"$recovery_stage/compose.yml"; chmod 0644 "$recovery_stage/compose.yml"
[ "$(node_recovery)" = OWNED_PARTIAL ] && [ "$(node_retire)" = RETIRED ] || exit 1
[ "$(node_retire)" = ABSENT ]
rm -f /tmp/remnawave-retire-removed
echo 'PASS retirement failures preserve files and owned partial/marker-only states retire idempotently'
echo 'LINUX SAFETY CHECKS PASSED'
