# Metric retention, maintenance windows and incident acknowledgement

## Metric retention and rollups

Synchronization keeps writing raw observations to `metric_observation`. A background upkeep cycle
(`MetricRetention`, every `INFRADESK_METRICS_MAINTENANCE_INTERVAL_SECONDS`, 300 by default) keeps
that history bounded:

1. **Rollup.** For each stored resolution (`FIVE_MINUTES`, `HOUR`) the cycle reads its watermark
   from `metric_rollup_watermark` (row-locked), computes the next bucket-aligned `until` — closed
   buckets only, two minutes after they end, at most six hours (five-minute) or two days (hourly)
   per step — and in one short transaction moves the watermark from `from` to `until` by
   compare-and-set and writes one `metric_rollup` row per resource, metric and bucket with count,
   minimum, maximum and average. Buckets are aligned to a fixed UTC origin (`date_bin`), so they never
   depend on the session time zone. Both ends of a step are aligned, every bucket is complete, and a
   recomputed bucket is identical; the upsert makes a repeated step harmless.
2. **Retention.** Raw rows are deleted below the lower of the lowest watermark and
   `now - INFRADESK_METRICS_RAW_RETENTION_DAYS` (7 by default); without a watermark nothing raw is
   deleted. Five-minute rollups are kept `INFRADESK_METRICS_FIVE_MINUTE_RETENTION_DAYS` (30), hourly
   ones `INFRADESK_METRICS_HOURLY_RETENTION_DAYS` (400). Deletion runs in statements of 10,000 rows,
   at most 20 per kind and cycle, so a backlog after an outage is worked off over several cycles.
   Retention must satisfy raw ≤ five-minute ≤ hourly and raw ≥ 1 day.

Each step is its own transaction under `pg_try_advisory_xact_lock`: one instance does the work, any
other skips its turn. A crash between steps leaves the watermark where the last committed step put
it. Observations written more than two minutes late for an already rolled-up bucket remain raw and
are not added to that bucket; synchronization writes observations at collection time, so this is
the rare case of a delayed transaction. V69 adds an index on `metric_observation(observed_at)`; on a
large table its creation briefly blocks writes during the upgrade.

`GET /api/v1/organizations/{id}/resources/{resourceId}/metric-series?from&to` returns
`{ resolution, from, to, points: [{ metricCode, bucketStart, average, minimum, maximum, samples }] }`.
The resolution follows the period: every observation (`RAW`) up to 6 hours, `FIVE_MINUTES` up to
3 days, `HOUR` up to 45 days and `DAY` (summarised from hours, each weighted by its samples) up to
the maximum of 400 days. Buckets below the watermark come from rollups, newer ones are computed
from raw rows in the same statement, so a chart reaches the present. Only metric codes the running
version knows are returned. The existing `/metrics` endpoint still returns raw observations; the
resource page will move to the series endpoint together with the telemetry metrics work.

## Maintenance windows

`maintenance_window` covers one resource and the resources directly beneath it (a server and its
containers) from `starts_at` to `ends_at` (at most 30 days), unless `cancelled_at` comes first.
Owners, administrators and operators (`MANAGE_MONITORING`) plan and end windows on the
Monitoring → Maintenance page or through:

- `GET /api/v1/organizations/{id}/maintenance-windows` — `{ now, windows }`, current and upcoming
  first, then the most recent finished, 200 at most; each with `state`
  (`SCHEDULED`, `ACTIVE`, `FINISHED`, `CANCELLED`) and `effectiveEnd`.
- `POST …/maintenance-windows` `{ resourceId, startsAt, endsAt, reason }` → 201. The resource must be
  an active resource of the organization; a window cannot start more than five minutes in the past,
  since backdating would silence incidents that were already due to notify.
- `POST …/maintenance-windows/{windowId}/cancel` → the window. Cancelling twice changes nothing;
  a finished window answers `409 MAINTENANCE_WINDOW_FINISHED`.

Monitoring keeps evaluating during a window and incidents open and resolve as usual, so history
stays true. The evaluation projection reads, in the same statement and at the evaluation's own time,
whether a window covers the rule's resource. An incident opened then is stored with
`notifications_silenced = true`; the outbox records no delivery for its opening or for its later
resolution, whenever that happens, for any channel including the deployment webhook. An incident
opened before a window keeps notifying, also when it resolves during the window. History events and
logs are recorded for silenced incidents as for any other. Creation and cancellation are journalled
(`MAINTENANCE_WINDOW_CREATED`, `MAINTENANCE_WINDOW_CANCELLED`) in the same transaction.

## Incident acknowledgement

`POST /api/v1/organizations/{id}/incidents/{incidentId}/acknowledge` (`MANAGE_MONITORING`) records
who took an open incident in hand and returns the incident. The first acknowledgement stands: a
second one returns the incident unchanged and is not journalled; a resolved incident answers
`409 INCIDENT_NOT_OPEN`; another organization's incident is `404`. The update is a single
conditional statement on the incident row, so it is ordered against a concurrent resolution, and
the evaluation's upsert never overwrites `acknowledged_at`, `acknowledged_by` or
`notifications_silenced`. Incidents carry `notificationsSilenced`, `acknowledgedAt` and
`acknowledgedByName` in every list and detail response. Acknowledgement does not change
notifications: there is no escalation to stop yet.
