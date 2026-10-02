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
prepare() { sh /checks/Prepare.sh "$target" /tmp/infradesk-check/input "$stage" 0 "$new" "$1" "$marker" false; }
commit() { sh /checks/Commit.sh "$target" "$stage/candidate" "$stage/previous" "$1" "$new"; }
prepare MISSING
[ "$(stat -c '%u:%a:%h' "$stage/candidate")" = '0:644:1' ]
commit MISSING
[ "$(sha256sum "$target" | cut -d' ' -f1)" = "$new" ]
echo 'PASS exclusive private staging and first atomic commit'
rm -f "$target"; rmdir "$stage"
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
echo 'LINUX SAFETY CHECKS PASSED'