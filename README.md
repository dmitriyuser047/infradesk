# InfraDesk

Before writing or changing code or performing Code Review, read the mandatory
[InfraDesk development and Code Review standard](docs/development-and-code-review-standard.md).
Agent workflow instructions are in [AGENTS.md](AGENTS.md).

Requirements: JDK 21, sbt 2, PostgreSQL 17 (or a compatible version), Node.js 24 LTS and npm for the frontend.

For the Docker Compose production topology, HTTPS boundary, backup/restore and rollback runbooks,
see [Running InfraDesk in production](docs/production.md).

Backend startup:

1. Create a PostgreSQL database and set the environment variables below (`.env.example` is a template, not automatically loaded).
2. Run `sbt run`.
3. Startup validates configuration, applies Flyway migrations, then starts the database-backed application and HTTP server. The scheduler runs while the server is active. A configuration or migration failure stops startup before the server starts.

| Variable | Required / default | Purpose |
| --- | --- | --- |
| `INFRADESK_DB_URL` | Required | PostgreSQL JDBC URL |
| `INFRADESK_DB_USER` | Required | Database user |
| `INFRADESK_DB_PASSWORD` | Required | Database password |
| `INFRADESK_DB_MAX_POOL_SIZE` | `10` | Maximum database connections per backend instance |
| `INFRADESK_DB_CONNECTION_TIMEOUT_SECONDS` | `10` | Pool connection timeout |
| `INFRADESK_SECRET_MASTER_KEY_BASE64` | Required | Base64-encoded 32-byte encryption key |
| `INFRADESK_HTTP_HOST` | `0.0.0.0` | HTTP bind host |
| `INFRADESK_HTTP_PORT` | `8080` | HTTP bind port |
| `INFRADESK_AUTH_SESSION_TTL_SECONDS` | `604800` | Session lifetime |
| `INFRADESK_AUTH_COOKIE_SECURE` | `false` | Secure cookie flag; set `true` behind HTTPS |
| `INFRADESK_SCHEDULER_ENABLED` | `true` | Start the database-claimed synchronization scheduler |
| `INFRADESK_SCHEDULER_POLL_INTERVAL_SECONDS` | `1` | Scheduler poll interval |
| `INFRADESK_SCHEDULER_BATCH_SIZE` | `100` | Schedules per poll |
| `INFRADESK_SCHEDULER_MAX_CONCURRENCY` | `5` | Maximum concurrently executing scheduled syncs |
| `INFRADESK_SCHEDULER_CLAIM_LEASE_SECONDS` | `900` | Distributed scheduler claim lease |
| `INFRADESK_NOTIFICATION_WEBHOOK_URL` | Optional | Webhook endpoint for incident events; absent disables notifications |
| `INFRADESK_NOTIFICATION_POLL_INTERVAL_SECONDS` | `5` | Delivery dispatcher poll interval |
| `INFRADESK_NOTIFICATION_BATCH_SIZE` | `50` | Deliveries a poll may handle, claimed in waves |
| `INFRADESK_NOTIFICATION_MAX_CONCURRENCY` | `5` | Concurrent webhook requests, and the size of one claim wave |
| `INFRADESK_NOTIFICATION_CLAIM_LEASE_SECONDS` | `60` | Delivery lease; must exceed the request timeout |
| `INFRADESK_NOTIFICATION_REQUEST_TIMEOUT_SECONDS` | `10` | Webhook request timeout |
| `INFRADESK_NOTIFICATION_MAX_ATTEMPTS` | `10` | Attempts before a delivery is abandoned |
| `INFRADESK_BOOTSTRAP_EMAIL`, `INFRADESK_BOOTSTRAP_PASSWORD`, `INFRADESK_BOOTSTRAP_ORGANIZATION_ID`, `INFRADESK_BOOTSTRAP_DISPLAY_NAME` | Optional, all-or-none | Initial admin for an existing organization |

Legacy `env:` SSH secret references use environment variables snapshotted at startup. Do not commit real credentials or encryption keys.

Tests: `sbt testFull` runs the complete backend suite. PostgreSQL integration tests require `INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true` and a database user allowed to create temporary test databases. They use the production `DatabaseMigrator` and clean up their temporary migration-test database.

## Application composition and startup lifecycle

`Main` is only an entrypoint; the object graph is assembled in `src/main/scala/bootstrap`:

| Unit | Responsibility |
| --- | --- |
| `InfraDeskApplication` | Startup and shutdown order, resource ownership |
| `PersistenceModule` | Repositories, transaction runner and readiness check over one transactor |
| `IntegrationModule` | SSH and Docker clients, secret cipher, connector registry |
| `ApplicationModule` | Use cases, authentication, bootstrap admin, sync scheduler |
| `HttpModule` | Routes, auth boundary, platform endpoints, request middleware |
| `AppLoggers` | The named loggers injected into the components that emit events |

Resource types are registered once in `PersistenceModule.resourceTypeCodecs` and resolved through
`ResourceDefinitionRegistry`. Generic persistence stores and reads whatever the registered typed
codec produces and hardcodes no NODE or CONTAINER knowledge, so a new resource type is a definition,
its codec and one registration line. A duplicate resource type code fails startup, and a type this
version does not know is readable only while its stored payload is empty. The frontend mirrors this:
a resource type is displayed by the presentation registered in
`frontend/src/components/resources/presentation/resourcePresentations.ts`, and a type it does not know
yet falls back to the common resource shell instead of failing.

Startup order:

```
config -> migrate -> database resource -> components -> bootstrap admin -> HTTP server -> scheduler
```

Wiring stays explicit constructor injection: no DI framework, no service locator, no reflection. The
component records exist only for assembly; business objects receive the individual dependencies they
need. Migration runs before any business runtime, and the scheduler runs inside the HTTP server
resource scope, so shutdown releases the server and then the database pool.

## Monitoring

A monitor rule watches one metric of one resource: `metric + operator + threshold + for duration +
no-data timeout`. Operators are `>`, `>=`, `<` and `<=`; thresholds of percentage metrics must be
between 0 and 100. The no-data timeout is how old the latest observation may become before the rule
reports that it cannot judge the resource; `0` turns that detection off.

Rule states: `OK`, `PENDING` (the condition is violated but not yet for the configured duration),
`FIRING` and `NO_DATA`. A rule has at most one open incident, and an incident records why it was
opened: `THRESHOLD` or `NO_DATA`. When the reason changes, the open incident is resolved and a new
one is opened in the same transaction.

Rules are evaluated after every synchronization of the connection that discovered the resource,
including a failed one, so a connection that stopped answering turns its rules into `NO_DATA`
instead of leaving them on stale data. The evaluation is a secondary step: it never changes the
outcome the synchronization itself reports.

SSH synchronization also collects agentless telemetry — disks, swap, load, I/O, network, local web
services, TLS expiry, drive health and container restarts and health checks — recorded as metrics
of servers and containers; see [Server and container telemetry](docs/telemetry.md).

Metric history is kept bounded by five-minute and hourly rollups with configurable retention;
maintenance windows silence the notifications of incidents opened while they cover a resource, and
open incidents can be acknowledged. See
[Metric retention, maintenance windows and incident acknowledgement](docs/metrics-maintenance-acknowledgement.md).

An organization has one SSH connection per server: creating or re-pointing a connection to a host
and port another connection already uses is refused with `CONNECTION_HOST_ALREADY_EXISTS`, checked
under an organization lock. `DELETE /api/v1/organizations/{id}/connections/{connectionId}` deletes a
connection, active or not, in one transaction: the row becomes an inactive tombstone (its code is
free again), its schedule stops, its credential is destroyed, and the servers that no other live
connection sees are deactivated together with everything beneath them. Sync sessions, operations,
deployments and the journal keep naming it. Deletion is refused with `CONNECTION_BUSY` while a
sync, operation, deployment, rollout item, provisioning run or onboarding that needs it is queued
or running; finished runs, including `UNKNOWN` ones, do not block it.

`GET /api/v1/organizations/{id}/incidents` is a read projection. Each incident carries the identity
of its resource (`resource: { id, name, resourceTypeCode }`), read in one tenant-scoped statement,
so the list never needs a request per row.

## Notifications

Incident events can be delivered to one deployment-level webhook; there is no per-user channel
configuration. Without `INFRADESK_NOTIFICATION_WEBHOOK_URL` the subsystem is off: nothing is
recorded and no dispatcher runs. A configured URL must be an absolute `http` or `https` endpoint,
is validated at startup and is never logged, because it may carry a token.

Delivery uses a transactional outbox. Opening or resolving an incident writes a
`notification_delivery` row in the same transaction as the incident change, so the two cannot
diverge; if the outbox write fails, the incident change rolls back with it. The dispatcher then
claims due rows with a lease (`for update skip locked`, like the sync scheduler), performs the HTTP
request outside any transaction, and finishes each delivery in its own short transaction fenced by
the claim. No database row is locked while a request is in flight.

A poll claims in waves of at most `INFRADESK_NOTIFICATION_MAX_CONCURRENCY` rows and takes the next
wave only after the current one is finished, up to `INFRADESK_NOTIFICATION_BATCH_SIZE`. A claimed
delivery is therefore always in flight rather than queued behind others, so the lease only has to
cover one request, and a crashed dispatcher freezes one wave instead of a whole batch.

The guarantee is at-least-once: a process that dies between a delivered request and its completion
retries after the lease expires. The delivery id is the event id, sent both as `eventId` in the
body and as the `X-InfraDesk-Event-Id` header, so receivers can deduplicate. `2xx` accepts an
event; `408`, `429`, `5xx`, timeouts and connection failures are retried with exponential backoff
(30s doubling to at most one hour) until `INFRADESK_NOTIFICATION_MAX_ATTEMPTS`; other `4xx`
responses end the delivery immediately.

## Controlled resource operations

An organization owner can start, stop or restart an active `CONTAINER` resource discovered through
one active SSH connection. The browser sends only a typed operation code; the backend resolves the
stored full Docker id and emits one fixed command (`docker start`, `docker stop --time 10`, or
`docker restart --time 10`). Arbitrary commands, arguments, connection selection and timeouts are
not part of the API.

Each request is journaled and creates a durable `operation_execution`. A partial unique index allows
only one `RUNNING` execution per resource. Target resolution, stale recovery, the RUNNING insert and
the audit event commit together; SSH runs after that transaction, and a second short transaction
records `SUCCEEDED` or `FAILED`. There is no automatic retry. Each execution stores the moment
after which its own attempt may be declared abandoned, computed when it starts from that
connection's connect and command timeouts plus a minute of margin, never less than ten minutes.
Recovery compares against that stored deadline, so reconfiguring the connection afterwards cannot
retire a command that is still in flight; the recovered execution becomes `UNKNOWN`, since the
backend cannot safely infer whether the remote command took effect. Owners see controls and history; members retain read-only history access.

## Activity history

InfraDesk keeps an append-only journal of what happened to the infrastructure: resources
discovered and deactivated, incidents opened and resolved, controlled operations requested and
finished, and failed synchronizations. It is a read model, not event sourcing — the resource,
incident, execution and session tables remain the source of truth, and nothing is ever rebuilt
from the journal.

Each entry carries typed references and no rendered message, so the timeline is presented by the
client and never repeats a detail the state already owns. An entry is written in the same
transaction as the change it reports: if the journal write fails, the incident, the operation or
the reconciliation fails with it. Metrics, notification deliveries and scheduler ticks are
deliberately absent — they are telemetry, transport and logs. The audit journal stays separate and
owner-only: it answers who changed the configuration, while this one answers what happened.

`GET /api/v1/organizations/{id}/history-events` and
`GET /api/v1/organizations/{id}/resources/{id}/history-events` return bounded, keyset-paginated
pages to any member of the organization.

## Operations Dashboard

The Overview page is the landing page of an organization: a fleet summary, what needs attention
now, and the recent activity, for the organization, one project or one environment. It is a
derived read model. Nothing about it is stored: resources, connections and their sync sessions,
incidents, operation executions and the activity history stay the sources of truth, and every
number is computed from them when the page is read.

`GET /api/v1/organizations/{id}/overview[?projectId=…[&environmentId=…]]` needs only read access,
so members see it as owners do. A project or environment outside the organization is not found.
One request runs in a single read-only snapshot: at most one scope check, one aggregate row for
the summary, at most 20 attention items and 15 activity entries. It opens no SSH session and makes
no other network call.

- Summary: active nodes by their stored `online` flag, active containers by their stored Docker
  state, active connections by the outcome of their latest finished synchronization, open
  incidents by reason, and resources whose latest operation of the last 24 hours failed or has an
  unknown result. A failure counts only while its resource is active; an unknown result counts
  even after inventory deactivated the resource, because its effect on the host still needs a
  manual check.
- Needs attention lists current problems only, ordered by the backend: unknown operation results,
  open incidents, offline nodes, failed synchronizations, failed operations. A connection appears
  once for its latest failure and disappears after a successful synchronization; the history keeps
  every failure.
- The page refreshes every 30 seconds and after a manual sync, an operation or a monitor rule
  change. It never predicts the effect of an operation; inventory synchronization does.

## Interface and localization

The interface is Russian by default, with English as the second language. The language switch is
in the account menu. It applies without a reload and is remembered in the browser under
`infradesk.locale`. Dictionaries live in `frontend/src/i18n` and are typed: a key missing from
either language fails the build. Domain codes such as statuses, error codes and resource types stay
in English in the API, and only their display text is translated. Error messages map a
backend error code to text in the active language and never show stack traces or internal
identifiers.

The layout has a sidebar with grouped sections, a top bar with the organization, project and
environment switcher, and the account menu. The URL stays the source of truth for the current
context: changing the project resets the environment, and changing the organization resets both.
The first-run guide on the Overview page is derived from existing data (projects, environments,
connections, synchronized resources) and is not stored. Styles use the design tokens in
`frontend/src/styles/tokens.css`, with no UI framework.

Frontend: `cd frontend`, then `npm ci`, `npm test`, `npm run build`, or `npm run dev`.

Applied Flyway migrations are immutable. Add a new versioned migration instead of modifying an applied one. An existing non-empty database without Flyway history is not auto-baselined; assess it before switching startup to Flyway.
