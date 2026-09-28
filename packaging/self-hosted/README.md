# InfraDesk — self-hosted installation

This bundle installs InfraDesk on one Linux server with Docker. You do not need the source code,
sbt, npm or to build any image: the installer pulls the exact, pre-built images this release was
tested with.

## Supported platforms

| | |
| --- | --- |
| Operating system | Ubuntu 22.04 LTS, Ubuntu 24.04 LTS, Debian 12 |
| Architecture | linux/amd64 |
| Runtime | Docker Engine 24 or newer with the Docker Compose v2 plugin (2.20+) |
| Memory | 2 GiB minimum, 4 GiB recommended |
| Disk | 10 GiB free for Docker and `/var/lib` |

The installer checks all of this first and stops, changing nothing, on anything else. If Docker is
missing it offers to install it from Docker's official apt repository (never without asking, and
with `--non-interactive` only when you pass `--install-docker`).

## Install

Download the bundle and the checksums **of the same release**, verify, extract, run:

```bash
curl -fsSLO https://github.com/dmitriyuser047/infradesk/releases/download/v0.1.0/infradesk-v0.1.0-linux-amd64.tar.gz
curl -fsSLO https://github.com/dmitriyuser047/infradesk/releases/download/v0.1.0/SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing
tar -xzf infradesk-v0.1.0-linux-amd64.tar.gz
cd infradesk-v0.1.0
sudo ./install.sh
```

Read `install.sh` before you run it if you like — it is plain Bash. It asks for:

* the address and port to listen on (default `127.0.0.1:8080`),
* whether users will reach InfraDesk over HTTPS (they should),
* the first administrator's email, name and password (typed silently, never a command argument),
* the name of the first organization.

It then works through eight steps — checking the system, preparing directories, generating the
configuration, pulling images, starting PostgreSQL, starting InfraDesk, waiting for readiness and
a smoke check — and prints the URL. `docker compose up` alone is not treated as success: the
installer waits until InfraDesk answers through its public entrypoint and reports the build of
this release.

> **Private repository.** While the InfraDesk repository and its images are private, downloading
> the release and pulling the images need a GitHub account with access. Download the two files
> in a browser, and before installing run `docker login ghcr.io` with a personal access token
> that has the `read:packages` scope. The installer itself never asks for or stores a token.

### Automated installation

```bash
sudo ./install.sh --non-interactive \
  --admin-email admin@example.com --admin-name "Ops Team" \
  --admin-password-file /root/infradesk-admin-password \
  --bind 127.0.0.1 --port 8080
```

The password may also come from the `INFRADESK_ADMIN_PASSWORD` environment variable. Without
either, a temporary password is generated and shown once on the terminal; change it after the first
sign-in (Account → Password).

### Running the installer again

Nothing is regenerated or reset: the installer sees the existing installation, says so, and exits.
`sudo ./install.sh --reconcile` re-runs the checks and starts InfraDesk again with the existing
configuration and data.

## Sign in

Open the printed URL and sign in with the administrator account. Then add your first SSH
connection (Connections → New connection), confirm the host key, and run a synchronization.

## Put it behind your reverse proxy

InfraDesk listens on `127.0.0.1:8080` by default and serves plain HTTP there. Publish it with the
reverse proxy you already run, over HTTPS. The installer does **not** touch nginx, Caddy, Apache,
your firewall or DNS.

InfraDesk has a browser SSH terminal that uses a WebSocket, so the proxy must pass
`Upgrade`/`Connection`, and allow long-lived connections (terminal sessions last up to two hours;
a manual synchronization can take up to 45 minutes).

### nginx

```nginx
map $http_upgrade $infradesk_connection_upgrade {
  default upgrade;
  ''      close;
}

server {
  listen 443 ssl;
  http2 on;
  server_name infradesk.example.com;
  ssl_certificate     /etc/letsencrypt/live/infradesk.example.com/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/infradesk.example.com/privkey.pem;

  client_max_body_size 1m;

  location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    # The browser terminal (/api/v1/organizations/.../connections/.../terminal) is a WebSocket.
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $infradesk_connection_upgrade;
    # InfraDesk bounds each route itself; these only must not be shorter than its longest one.
    proxy_read_timeout 7500s;
    proxy_send_timeout 7500s;
  }
}

server {
  listen 80;
  server_name infradesk.example.com;
  return 301 https://$host$request_uri;
}
```

### Caddy

```caddy
infradesk.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Caddy obtains the certificate, forwards WebSockets and sets `X-Forwarded-For` and
`X-Forwarded-Proto` on its own.

InfraDesk trusts a forwarded client address only from the host side of its private Docker network,
which is where a proxy on this host connects from. A proxy on another machine must reach this
host's port directly; it cannot pass client addresses for the login rate limit.

### Plain HTTP for a test machine

`sudo ./install.sh --bind 0.0.0.0 --port 8080 --no-https` makes InfraDesk reachable at
`http://SERVER_IP:8080` without a proxy. The sign-in cookie is then not HTTPS-only. Use this only
on a trusted network: the installer does not change your firewall.

## Everyday commands

All commands work from any directory and never print secrets.

| Command | What it does |
| --- | --- |
| `sudo infradesk status` | Services, readiness and the URL |
| `sudo infradesk logs [backend\|frontend\|postgres]` | The last 200 log lines |
| `sudo infradesk restart` / `stop` / `start` | Restart, stop or start; data is never removed |
| `infradesk version` | The installed bundle and the build the backend reports; warns if they differ |
| `sudo infradesk doctor` | Diagnostics you can send to a developer (no secret values) |
| `sudo infradesk backup` | A verified database backup |
| `sudo infradesk restore FILE` | Replace the database with a backup |
| `sudo infradesk update [vX.Y.Z]` | Update to a release |
| `sudo infradesk uninstall` | Remove InfraDesk, keeping data |

Containers restart on their own after a reboot (`restart: unless-stopped`).

## Update

```bash
sudo infradesk update           # the latest stable release
sudo infradesk update v0.1.1    # an exact release
```

The update downloads the release bundle and its `SHA256SUMS` from this project's GitHub
Releases, verifies the checksum, and then:

1. backs up the database to `/var/backups/infradesk/pre-update-<version>-<time>.dump` — if this
   fails, the update stops before anything changes;
2. pulls the new images;
3. replaces the files in `/opt/infradesk` (never `/etc/infradesk/infradesk.env`);
4. runs `docker compose up -d` — the backend migrates the database on start;
5. waits for readiness and checks the new build identity.

No data is removed and nothing is torn down with `down -v`. If you downloaded the files yourself
(for example from a private repository):

```bash
sudo infradesk update --from-file infradesk-v0.1.1-linux-amd64.tar.gz --sha256sums SHA256SUMS
```

**There is no automatic downgrade.** Once a new release has started, its migrations may already
have changed the database, and an older release may not run against it. If an update fails, the
message tells you what was kept. To go back: install the older bundle again and restore the
pre-update backup.

## Backup

```bash
sudo infradesk backup
```

writes `/var/backups/infradesk/infradesk-YYYYMMDD-HHMMSS.dump` (PostgreSQL custom format, read back
before it is reported as written) and a `.meta` file with the version, commit and schema. Keep
copies off this server as well — for example with a nightly cron job:

```
15 2 * * * /usr/local/bin/infradesk backup >> /var/log/infradesk-backup.log 2>&1
```

### IMPORTANT: back up the configuration file too

`/etc/infradesk/infradesk.env` contains the **encryption key** for the SSH credentials stored in the
database, and the database password. `infradesk backup` deliberately does not copy it next to the
dumps. Back it up separately and securely (a password manager or a secrets store).

**Without this key, a restored database has all its data but no usable SSH credentials** — every
connection would have to be given its password or key again.

## Restore

```bash
sudo infradesk restore /var/backups/infradesk/infradesk-20260928-120000.dump
```

Restore is destructive and asks you to type `restore` (`--yes` for automation). It checks that the
file is a readable InfraDesk backup and that this release supports its schema (a backup from a
newer release is refused before anything changes), stops the application while PostgreSQL keeps
running, takes an emergency backup of the current database, replaces the database, starts
InfraDesk — which migrates an older schema forward — and waits for readiness.

For a full disaster recovery on a new server: install the same release, put your saved
`/etc/infradesk/infradesk.env` in place **before** the first start is needed, and restore the dump.
If the configuration file is already restored when you run `install.sh`, it is reused.

## Uninstall

```bash
sudo infradesk uninstall
```

stops and removes the containers, the CLI and `/opt/infradesk`, and **keeps** the database, the
configuration and the backups. Installing again later reuses them.

```bash
sudo infradesk uninstall --purge-data
```

also deletes the database and the configuration (it asks twice). Backups are always kept. No
command of InfraDesk ever runs `docker system prune` or removes anything that is not its own.

## Where things are

| Path | Contents |
| --- | --- |
| `/opt/infradesk` | This release's compose file, scripts, manifest and `install-state` (no secrets) |
| `/etc/infradesk/infradesk.env` | Configuration and secrets — root only, mode 600 |
| `/var/lib/infradesk/postgres` | The PostgreSQL database |
| `/var/backups/infradesk` | Backups — root only |
| `/usr/local/bin/infradesk` | The command line |

Only the web entrypoint publishes a port. PostgreSQL and the backend are reachable only on
InfraDesk's private Docker network.

## Configuration reference

`/etc/infradesk/infradesk.env` (see `.env.example` in this bundle):

| Variable | Required | Secret | Meaning |
| --- | --- | --- | --- |
| `INFRADESK_DB_PASSWORD` | yes | yes | PostgreSQL password, generated once |
| `INFRADESK_SECRET_MASTER_KEY_BASE64` | yes | yes | Encryption key for stored credentials, 32 bytes Base64, generated once, never change it |
| `INFRADESK_HTTP_PUBLISH` | yes | no | `ADDRESS:PORT` InfraDesk listens on, e.g. `127.0.0.1:8080` |
| `INFRADESK_AUTH_COOKIE_SECURE` | no (`true`) | no | `false` only for plain-HTTP testing |
| `INFRADESK_INTERNAL_SUBNET` | yes | no | Private /24 for InfraDesk's Docker network |
| `INFRADESK_TRUSTED_PROXY_CIDR` | yes | no | The `.1/32` gateway of that subnet |
| `INFRADESK_NOTIFICATION_WEBHOOK_URL` | no | no | Optional notification webhook |
| `INFRADESK_BACKEND_MEMORY_LIMIT`, `INFRADESK_POSTGRES_MEMORY_LIMIT` | no (`1g`) | no | Container memory limits |

After editing the file, apply it with `sudo infradesk restart`… or, for a changed listen address,
`sudo /opt/infradesk/install.sh --reconcile`. Everything else (workers, pools, timeouts) keeps
InfraDesk's built-in defaults.

## Troubleshooting

| Symptom | What to do |
| --- | --- |
| `cannot bind 127.0.0.1:8080` | Another service uses the port. Re-run with `--port 8081`. Nothing was started. |
| Images cannot be pulled | Check network access to `ghcr.io`; for a private repository, `docker login ghcr.io` first. Re-run the installer. |
| Not ready within 180 seconds | `sudo infradesk logs backend` — configuration and migration errors name the problem. Data is kept; re-run the installer. |
| The sign-in page loads but signing in does nothing | You opened InfraDesk over plain HTTP while it expects HTTPS. Use your HTTPS reverse proxy (or `--no-https` on a test machine). |
| Connections fail to authenticate after a restore | The configuration file is not the one the backup was taken under: restore `/etc/infradesk/infradesk.env`. |
| `Installed bundle and running backend do not match` | Run `sudo infradesk doctor`; `sudo /opt/infradesk/install.sh --reconcile` starts the installed release again. |
| Anything else | `sudo infradesk doctor` and send its output (it contains no secrets). |
