# Running InfraDesk in production

This is what one person needs to deploy InfraDesk on a clean Linux host and keep it running. It
assumes Docker with the Compose plugin, a domain name, and a TLS terminator in front of the host.

> Installing a released version without the source tree? Use the self-hosted release bundle and
> its installer instead: see [`packaging/self-hosted/README.md`](../packaging/self-hosted/README.md).
> This document describes the source-based deployment used for development and CI.

## Topology

```
Internet → TLS terminator → proxy container (nginx)  ┐
                                ├── /            static frontend bundle
                                └── /api, /health, /ready → backend container
                                                     backend → postgres container
```

Only the proxy publishes a port, and by default only on the loopback interface, so the TLS
terminator in front of it is the single way in. The backend and the database are reachable on the
internal Compose network and nowhere else — PostgreSQL has no published port at all.

## Requirements

* Docker Engine 24+ with the Compose plugin.
* A host clock synchronized by NTP. The scheduler claims, the notification leases, the operation
  and synchronization recovery deadlines and session expiry are all compared against timestamps:
  a drifting clock retires work that is still running.
* UTC. The containers set `TZ=UTC`; timestamps are stored as `timestamptz` and rendered in the
  viewer's local time by the browser.
* A TLS terminator (nginx, Caddy, Traefik or a cloud load balancer). InfraDesk sets an
  authentication cookie and must not be reachable over plain HTTP: redirect port 80 to 443 and
  forward `X-Forwarded-Proto: https` and the client address in `X-Forwarded-For`.

### Trusted proxy boundary

The proxy container trusts `X-Forwarded-For` only from `INFRADESK_TRUSTED_PROXY_CIDR`. The default
is the gateway of the dedicated Compose subnet (`172.28.0.1/32`), which is where a TLS terminator
running on the same host reaches the published loopback port. Direct clients and other containers
cannot choose the login rate-limit key by sending their own forwarding header. If the Compose
subnet conflicts with another Docker network, change `INFRADESK_INTERNAL_SUBNET` and set
`INFRADESK_TRUSTED_PROXY_CIDR` to that subnet's host gateway `/32`; never use `0.0.0.0/0`.

## Configuration

Everything is read from the environment at startup, and anything invalid stops the process before
it serves a request: a missing database URL, user or password, a malformed port, a pool size
outside 1–100, an unusable encryption key, a webhook URL that is not absolute `http`/`https`, or a
notification lease shorter than its request timeout.

SSH settings are also bounded: connect timeout is at most 60 seconds and command timeout at most
1200 seconds. Manual sync and controlled-operation routes receive a 45-minute nginx budget, which
is longer than the maximum backend transport budget plus its recovery margin. Ordinary API
routes retain a 60-second proxy timeout.

Copy [`.env.production.example`](../.env.production.example) to a host path outside the
repository — `/etc/infradesk/infradesk.env`, mode `600`, owned by root — and fill it in. The
compose file reads it and contains no secrets itself:

```bash
docker compose --env-file /etc/infradesk/infradesk.env -f compose.prod.yml up -d
```

### The encryption key

`INFRADESK_SECRET_MASTER_KEY_BASE64` decrypts the SSH credentials already stored in the database.
Create it once:

```bash
openssl rand -base64 32
```

Then keep it. It is **not** regenerated per deploy, it is not derived from anything, and it is not
part of a database dump. A restore without the original key gives you every row and no usable
connection. Store it in a password manager or a secrets service, separately from the backups, and
never log or echo it.

## First deployment

1. Install Docker and the Compose plugin; make sure NTP is running.
2. Create `/opt/infradesk` and put `compose.prod.yml` and the `deploy/` directory there, or clone
   the repository at the commit you are deploying.
3. Create `/etc/infradesk/infradesk.env` from the template; set the database password and the
   encryption key. Leave all four `INFRADESK_BOOTSTRAP_*` values empty for this first start.
4. Point your TLS terminator at `INFRADESK_HTTP_PUBLISH` (`127.0.0.1:8081` by default).
5. Build or pull the images, tagged by commit:
   ```bash
   docker build -f deploy/backend.Dockerfile  -t infradesk-backend:$(git rev-parse --short HEAD) \
     --build-arg INFRADESK_GIT_SHA=$(git rev-parse HEAD) \
     --build-arg INFRADESK_BUILD_VERSION=0.1.0-$(git rev-parse --short HEAD) .
   docker build -f deploy/frontend.Dockerfile -t infradesk-frontend:$(git rev-parse --short HEAD) \
     --build-arg INFRADESK_GIT_SHA=$(git rev-parse HEAD) .
   ```
6. Start once to create the schema:
   `docker compose --env-file /etc/infradesk/infradesk.env -f compose.prod.yml up -d`.
7. Wait for readiness: `scripts/check-production.sh https://infradesk.example.com`.
8. Create the first organization. Keep the printed UUID:
   ```bash
   organization_id="$(uuidgen)"
   docker compose --env-file /etc/infradesk/infradesk.env -f compose.prod.yml \
     exec -T postgres sh -c \
     "psql --username \"\$POSTGRES_USER\" --dbname \"\$POSTGRES_DB\" --set ON_ERROR_STOP=1 \
       --command \"insert into organization (id, code, name) values ('$organization_id'::uuid, 'default', 'Default organization')\""
   printf '%s\n' "$organization_id"
   ```
9. Put `INFRADESK_BOOTSTRAP_EMAIL`, `INFRADESK_BOOTSTRAP_PASSWORD`,
   `INFRADESK_BOOTSTRAP_ORGANIZATION_ID` (the UUID above), and
   `INFRADESK_BOOTSTRAP_DISPLAY_NAME` in the environment file, then recreate the backend:
   `docker compose --env-file /etc/infradesk/infradesk.env -f compose.prod.yml up -d --force-recreate backend`.
10. Wait for `/ready` and sign in as the bootstrap administrator to verify the account.
11. Empty all four `INFRADESK_BOOTSTRAP_*` values and recreate the backend again. Bootstrap is
    idempotent: it creates a missing account and membership, but never rewrites an existing
    password.
12. Create the first SSH connection and confirm a synchronization completes.

## Upgrade

For user creation, organization roles and initial global administrator provisioning, see
[Users, organizations and administration](administration.md). Existing installations whose
bootstrap credentials were removed must explicitly select an existing active administrator.

1. The commit is green in CI.
2. Note the currently deployed tag: `docker compose -f compose.prod.yml images`.
3. Take a backup if the release contains a risky migration (see below).
4. Pull or build the new images and set the new tags in the environment file.
5. `docker compose --env-file … -f compose.prod.yml up -d`. The backend runs Flyway itself, and
   HTTP, the scheduler and the notification dispatcher start only after the migration succeeded.
6. `scripts/check-production.sh https://infradesk.example.com`
7. `docker compose -f compose.prod.yml logs backend | grep application.started` reports the
   version and commit baked into the artifact. Runtime environment variables cannot replace this
   identity; compare the SHA with the immutable image tag you selected.

### Migration policy

Migrations are forward-only and, wherever possible, additive: add a table, add a nullable column,
add an index. Applied migrations are never edited — Flyway verifies their checksums and refuses to
start if one changed. A destructive change (drop, rename, narrowing type change) is done over two
releases, expand then contract, so the previous image still runs against the new schema. There is
no Flyway Undo: a rollback means going back to the previous image, not backwards through the
schema.

## Rollback

1. Preserve the logs of the broken version: `docker compose -f compose.prod.yml logs backend > /tmp/broken.log`.
2. Set the previous image tags in the environment file.
3. `docker compose --env-file … -f compose.prod.yml up -d`
4. `scripts/check-production.sh https://infradesk.example.com`

This works while the new release's migrations were compatible with the old image, which the policy
above is there to guarantee. If the migration itself is the problem, restore instead.

## Backup

```bash
INFRADESK_ENV_FILE=/etc/infradesk/infradesk.env \
  INFRADESK_BACKUP_DIR=/var/backups/infradesk scripts/backup-database.sh
```

The script writes `infradesk-<timestamp>.dump` (custom format, mode 600) through the compose
service, so no database port is published and no password appears in an argument list, then
deletes dumps older than `INFRADESK_BACKUP_RETENTION_DAYS` (14 by default). Run it from cron:

```
15 2 * * * cd /opt/infradesk && INFRADESK_ENV_FILE=/etc/infradesk/infradesk.env INFRADESK_BACKUP_DIR=/var/backups/infradesk scripts/backup-database.sh >> /var/log/infradesk-backup.log 2>&1
```

A backup on the same host protects against a bad migration, not against losing the host. Copy the
dumps to object storage or another machine, and keep the encryption key somewhere else again.

## Restore

Restore is destructive. The script stops the backend and proxy, drops and recreates the named
application database inside the existing PostgreSQL volume, restores the custom-format dump, and
starts the topology again. It deliberately refuses to replace `postgres`, `template0`, or
`template1`.

1. Preserve current logs and verify the dump path and target environment file.
2. Make sure the environment file carries the **same** `INFRADESK_SECRET_MASTER_KEY_BASE64` the
   dump was taken under.
3. Run:
   `INFRADESK_ENV_FILE=/etc/infradesk/infradesk.env scripts/restore-database.sh /var/backups/infradesk/infradesk-2026-09-24T020000Z.dump`.
4. The restarted backend runs Flyway before HTTP, bringing an older restored schema to the version
   required by the current image.
5. Run `scripts/check-production.sh https://infradesk.example.com`, verify
   `application.started` reports the expected image SHA, and check that organizations, resources and connections are
   present, and that a synchronization still authenticates.

## SSH connections

A connection authenticates with a password or with a private key, and it only ever talks to a
host whose identity it already trusts.

Recommended setup for a managed node:

1. Create a dedicated user rather than using `root`:
   ```bash
   sudo adduser --disabled-password --gecos "InfraDesk" infradesk
   ```
2. Generate a key pair for InfraDesk alone, on your workstation, and install the public half:
   ```bash
   ssh-keygen -t ed25519 -C "infradesk" -f ~/.ssh/infradesk
   ssh-copy-id -i ~/.ssh/infradesk.pub infradesk@node.example.com
   ```
3. Container operations and Docker inventory talk to the local Docker daemon, so the user needs
   access to its socket: `sudo usermod -aG docker infradesk`. That group grants root-equivalent
   power on the host — give it to this user on nodes you intend InfraDesk to operate, and nowhere
   else.
4. In InfraDesk, open the connection form, press **Read host key**, compare the fingerprint with
   what the node itself reports, and confirm it:
   ```bash
   ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
   ```
5. Choose **Private key** as the authentication method and paste the private half (plus its
   passphrase, if it has one). The key is encrypted with the deployment's encryption key and is
   never returned by the API, written to a log, or stored in the audit journal.

### Host identity

The confirmed fingerprint is a pinned value. Every synchronization, every manual sync and every
controlled operation verifies it during the SSH handshake, before authenticating — a host that
presents a different key is refused with `SSH_HOST_KEY_MISMATCH` and never receives the
credential. A host that was never confirmed is refused with `SSH_HOST_KEY_NOT_TRUSTED`.

Nothing accepts a new host key on its own. If you rebuild a node or rotate its host key, the
connection stops working until someone with `ManageConnections` reads the new key, compares it,
confirms the replacement and saves — which is recorded as a connection update in the audit
journal.

Changing other settings, or the schedule, never touches the stored credential: leave the
credential field blank and it is kept. Switching between password and private key does require a
new credential, so a connection can never carry one method with the other method's secret.

## Health, logs and limits

* `GET /health` — liveness. No SQL, answers as long as the process is alive.
* `GET /ready` — readiness. One lightweight database query with a three-second budget of its
  own, so a pool configured to wait minutes for ordinary work does not make readiness wait too.
  While PostgreSQL is unavailable it answers `503 NOT_READY` — the application reporting itself
  unavailable, never a `504` from the proxy, which is what makes a rolling restart wait. The
  budgets are layered deliberately: readiness 3s < proxy 7s < client.
* Logs go to stdout and are collected by Docker; the compose file caps them at 5 × 10 MB per
  service. Nothing writes application log files inside a container.
* Memory limits are set per service in the compose file and can be tuned per host.

## Troubleshooting

| Symptom | Where to look |
| --- | --- |
| Backend restarts at startup | `docker compose logs backend` — configuration and migration failures are reported with the offending key, never with credentials. |
| `/ready` non-200 | PostgreSQL container health, then `docker compose logs postgres`. |
| Login returns 429 | The proxy rate-limits `/api/v1/auth/login` (10/min, burst 5) per address. |
| A synchronization never finishes | It is retired only after its own deadline, computed from that connection's connect and command timeouts plus a minute. Check the connection's timeouts, then the Activity timeline for `SYNC_FAILED`. |
| Connections fail to authenticate after a restore | The encryption key does not match the one the secrets were stored with. |
