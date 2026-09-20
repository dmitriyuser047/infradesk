# OPS Architecture

## Modules

### core
Business infrastructure objects.
Must not depend on integrations, monitoring or automation.

### integration
External system connections and synchronization.
May depend on core.

### monitoring
Health checks, states, metrics and incidents.
May depend on core.

### automation
Scheduled and automated actions.
May depend on core, integration and monitoring.

## Rules

1. Core never contains connector-specific fields.
2. External systems are identified through external IDs.
3. Business object modifications go through Global API/ApiRop.
4. UI code does not update database tables directly.
5. ASelect/ASQL is used for query-oriented operations, not as a replacement for object API.
6. Related object creation is atomic when it represents one business operation.
7. No generic Ops_BaseEntity until repeated real use cases justify it.
8. No premature TxIndex/cache abstractions.
9. Secrets are not stored as plaintext business attributes.