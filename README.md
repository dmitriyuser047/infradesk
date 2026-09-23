# InfraDesk

Requirements: JDK 21, sbt 2, PostgreSQL 17 (or a compatible version), Node.js 24 LTS and npm for the frontend.

Backend startup:

1. Create a PostgreSQL database and set the environment variables below (`.env.example` is a template, not automatically loaded).
2. Run `sbt run`.
3. Startup validates configuration, applies Flyway migrations, then starts the database-backed application and HTTP server. The scheduler runs while the server is active. A configuration or migration failure stops startup before the server starts.

| Variable | Required / default | Purpose |
| --- | --- | --- |
| `INFRADESK_DB_URL` | Required | PostgreSQL JDBC URL |
| `INFRADESK_DB_USER` | Required | Database user |
| `INFRADESK_DB_PASSWORD` | Required | Database password |
| `INFRADESK_SECRET_MASTER_KEY_BASE64` | Required | Base64-encoded 32-byte encryption key |
| `INFRADESK_HTTP_HOST` | `0.0.0.0` | HTTP bind host |
| `INFRADESK_HTTP_PORT` | `8080` | HTTP bind port |
| `INFRADESK_AUTH_SESSION_TTL_SECONDS` | `604800` | Session lifetime |
| `INFRADESK_AUTH_COOKIE_SECURE` | `false` | Secure cookie flag; set `true` behind HTTPS |
| `INFRADESK_SCHEDULER_POLL_INTERVAL_SECONDS` | `1` | Scheduler poll interval |
| `INFRADESK_SCHEDULER_BATCH_SIZE` | `100` | Schedules per poll |
| `INFRADESK_SCHEDULER_MAX_CONCURRENCY` | `5` | Maximum concurrently executing scheduled syncs |
| `INFRADESK_SCHEDULER_CLAIM_LEASE_SECONDS` | `900` | Distributed scheduler lease; matches the 15-minute stale sync horizon |
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

Frontend: `cd frontend`, then `npm ci`, `npm test`, `npm run build`, or `npm run dev`.

Applied Flyway migrations are immutable. Add a new versioned migration instead of modifying an applied one. An existing non-empty database without Flyway history is not auto-baselined; assess it before switching startup to Flyway.
