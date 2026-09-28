#!/usr/bin/env bash
# Runs the whole self-hosted test suite inside one privileged Ubuntu container that plays a clean
# server with its own Docker daemon — the same checks CI runs, on a developer machine.
#
# Prepare images on the machine's Docker first (see packaging/self-hosted/tests/README.md), then:
#
#   docker run --rm --privileged -v <repo>:/repo:ro -v <dir with images.tar>:/work \
#     -v infradesk-e2e-docker:/var/lib/docker ubuntu:24.04 bash /repo/packaging/self-hosted/tests/dind-host.sh <git-sha>
set -Eeuo pipefail

GIT_SHA="$1"
step() { printf '\n######## %s\n' "$*"; }

step "Tools of a clean server: Docker Engine, Compose v2, and what the tests use"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq docker.io docker-compose-v2 curl openssl iproute2 python3 nodejs shellcheck ca-certificates >/dev/null
dockerd > /var/log/dockerd.log 2>&1 &
for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 1; done
docker version --format 'Docker {{.Server.Version}}'; docker compose version

step "Source tree with Unix line endings"
mkdir -p /src
tar -C /repo --exclude=./target --exclude=./project/target --exclude=./frontend/node_modules --exclude=./.git -cf - . | tar -C /src -xf -
find /src/packaging /src/scripts /src/deploy -type f -exec sed -i 's/\r$//' {} +
chmod +x /src/packaging/self-hosted/*.sh /src/packaging/self-hosted/infradesk /src/packaging/self-hosted/tests/*.sh
cd /src

step "ShellCheck"
shellcheck --severity=warning --external-sources --source-path=SCRIPTDIR \
  packaging/self-hosted/install.sh packaging/self-hosted/update.sh packaging/self-hosted/backup.sh \
  packaging/self-hosted/restore.sh packaging/self-hosted/uninstall.sh packaging/self-hosted/infradesk \
  packaging/self-hosted/lib/common.sh packaging/self-hosted/build-bundle.sh packaging/self-hosted/tests/*.sh

step "Installer unit tests"
bash packaging/self-hosted/tests/unit.sh

step "Images into a local registry, pinned by digest"
docker load -q -i /work/images.tar
docker run --detach --name ci-registry --publish 127.0.0.1:5000:5000 registry:2 >/dev/null
docker tag postgres:17 localhost:5000/postgres:17
for image in localhost:5000/infradesk-backend:0.0.1 localhost:5000/infradesk-backend:0.0.2 \
  localhost:5000/infradesk-frontend:ci localhost:5000/postgres:17; do
  docker push --quiet "${image}" >/dev/null
done
digest() { docker inspect --format '{{index .RepoDigests 0}}' "$1"; }
for version in 0.0.1 0.0.2; do
  packaging/self-hosted/build-bundle.sh --version "v${version}" --git-sha "${GIT_SHA}" \
    --backend-image "$(digest "localhost:5000/infradesk-backend:${version}")" \
    --frontend-image "$(digest localhost:5000/infradesk-frontend:ci)" \
    --postgres-image "$(digest localhost:5000/postgres:17)" --output "dist/${version}"
done
# The installer must pull what the bundle names.
docker image rm localhost:5000/infradesk-backend:0.0.1 localhost:5000/infradesk-backend:0.0.2 \
  localhost:5000/infradesk-frontend:ci localhost:5000/postgres:17 postgres:17 >/dev/null

step "Installer preflight on clean Ubuntu and Debian containers"
packaging/self-hosted/tests/preflight.sh dist/0.0.1

step "End to end"
bash packaging/self-hosted/tests/e2e.sh dist/0.0.1 dist/0.0.2 "${GIT_SHA}"

printf '\nLOCAL SELF-HOSTED SUITE PASSED\n'
