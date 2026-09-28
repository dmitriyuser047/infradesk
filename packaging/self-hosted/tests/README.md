# Self-hosted installer tests

| Script | What it checks | Needs |
| --- | --- | --- |
| `unit.sh` | Platform detection, validation, version and subnet arithmetic, manifest and env parsing, port listeners, Docker-absent behaviour | Bash only |
| `preflight.sh DIST` | The real installer in clean Ubuntu/Debian containers without Docker: stops at the right check, creates nothing | Docker |
| `e2e.sh DIST_A DIST_B SHA` | Install, operate, back up, restore, update and uninstall two built bundles on a real host, as root | A disposable Linux host with Docker |
| `dind-host.sh SHA` | All of the above inside one privileged Ubuntu 24.04 container with its own Docker daemon | Docker (Linux, Docker Desktop, WSL) |

CI runs them in the `self-hosted` job. To run the whole suite locally, build the images on your
Docker, save them, and start the throwaway server container:

```bash
sha=$(git rev-parse HEAD)
for v in 0.0.1 0.0.2; do
  docker build -f deploy/backend.Dockerfile --build-arg INFRADESK_GIT_SHA=$sha \
    --build-arg INFRADESK_BUILD_VERSION=$v -t localhost:5000/infradesk-backend:$v .
done
docker build -f deploy/frontend.Dockerfile --build-arg INFRADESK_GIT_SHA=$sha -t localhost:5000/infradesk-frontend:ci .
docker pull postgres:17 && docker pull registry:2
mkdir -p /tmp/infradesk-e2e
docker save -o /tmp/infradesk-e2e/images.tar localhost:5000/infradesk-backend:0.0.1 \
  localhost:5000/infradesk-backend:0.0.2 localhost:5000/infradesk-frontend:ci postgres:17 registry:2
docker run --rm --privileged -v "$PWD":/repo:ro -v /tmp/infradesk-e2e:/work \
  -v infradesk-e2e-docker:/var/lib/docker ubuntu:24.04 \
  bash /repo/packaging/self-hosted/tests/dind-host.sh "$sha"
```

It ends with `LOCAL SELF-HOSTED SUITE PASSED`. Remove the `infradesk-e2e-docker` volume afterwards
if you want the disk space back. `e2e.sh` must never run on a machine you care about: it installs
into `/opt`, `/etc`, `/var/lib` and purges what it installed.
