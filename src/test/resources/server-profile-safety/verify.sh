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
echo 'LINUX SAFETY CHECKS PASSED'
