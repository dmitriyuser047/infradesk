CREATE TABLE connection_schedule (
  organization_id  uuid        NOT NULL,
  connection_id    uuid        NOT NULL,
  enabled          boolean     NOT NULL,
  interval_seconds bigint      NOT NULL,
  next_run_at      timestamptz NOT NULL,

  CONSTRAINT pk_connection_schedule
    PRIMARY KEY (
      organization_id,
      connection_id
    ),

  CONSTRAINT fk_connection_schedule_connection_tenant
    FOREIGN KEY (
      connection_id,
      organization_id
    )
    REFERENCES connection (
      id,
      organization_id
    ),

  CONSTRAINT ck_connection_schedule_interval_seconds_positive
    CHECK (interval_seconds > 0)
);

CREATE INDEX ix_connection_schedule_due
  ON connection_schedule (next_run_at)
  WHERE enabled;
