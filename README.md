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

Frontend: `cd frontend`, then `npm ci`, `npm test`, `npm run build`, or `npm run dev`.

Applied migration files (`V1` through `V12`) are immutable. Add a new versioned migration for schema changes. An existing non-empty database without Flyway history is not auto-baselined; assess it before switching startup to Flyway.
