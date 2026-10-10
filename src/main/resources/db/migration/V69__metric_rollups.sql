-- Raw observations are kept for days; older history lives on as five-minute and hourly rollups.
-- A rollup row is a pure function of the raw rows of its bucket, so recomputing one is harmless.
CREATE TABLE metric_rollup (
  organization_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  metric_code varchar(64) NOT NULL,
  resolution varchar(16) NOT NULL CHECK (resolution IN ('FIVE_MINUTES','HOUR')),
  bucket_start timestamptz NOT NULL,
  sample_count integer NOT NULL CHECK (sample_count > 0),
  min_value numeric NOT NULL,
  max_value numeric NOT NULL,
  avg_value numeric NOT NULL,
  PRIMARY KEY (organization_id, resource_id, metric_code, resolution, bucket_start),
  FOREIGN KEY (resource_id, organization_id) REFERENCES resource(id, organization_id),
  CHECK (min_value <= avg_value AND avg_value <= max_value)
);
-- Retention deletes by age across every tenant.
CREATE INDEX ix_metric_rollup_age ON metric_rollup (resolution, bucket_start);

-- How far each resolution has been rolled up. Raw rows are deleted only below every watermark,
-- so no observation disappears before its buckets exist.
CREATE TABLE metric_rollup_watermark (
  resolution varchar(16) PRIMARY KEY CHECK (resolution IN ('FIVE_MINUTES','HOUR')),
  rolled_up_until timestamptz NOT NULL
);

-- Rollup and retention read raw rows by age across every tenant.
CREATE INDEX ix_metric_observation_observed_at ON metric_observation (observed_at);

-- Existing history is rolled up from its first hour on; an empty installation starts now.
INSERT INTO metric_rollup_watermark (resolution, rolled_up_until)
SELECT resolution, date_bin('1 hour', coalesce((SELECT min(observed_at) FROM metric_observation), now()),
  timestamptz '2000-01-01 00:00:00+00')
FROM (VALUES ('FIVE_MINUTES'), ('HOUR')) AS r(resolution);
