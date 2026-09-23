ALTER TABLE connection_schedule
  ADD COLUMN consecutive_failures bigint NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_connection_schedule_consecutive_failures_nonnegative
    CHECK (consecutive_failures >= 0);

UPDATE connection_schedule AS cs
SET interval_seconds = 300,
    next_run_at = greatest(cs.next_run_at, now() + interval '5 minutes')
FROM connection AS c
WHERE c.id = cs.connection_id
  AND c.organization_id = cs.organization_id
  AND c.connector_type = 'SSH'
  AND cs.interval_seconds < 300;
