CREATE TABLE metric_observation (
  id              uuid        PRIMARY KEY,
  organization_id uuid        NOT NULL,
  resource_id     uuid        NOT NULL,
  metric_code     varchar(64) NOT NULL,
  value           numeric     NOT NULL,
  observed_at     timestamptz NOT NULL,

  CONSTRAINT fk_metric_observation_resource_tenant
    FOREIGN KEY (
      resource_id,
      organization_id
    )
    REFERENCES resource (
      id,
      organization_id
    )
);

CREATE INDEX ix_metric_observation_resource_observed_at
  ON metric_observation (
    organization_id,
    resource_id,
    observed_at DESC
  );
