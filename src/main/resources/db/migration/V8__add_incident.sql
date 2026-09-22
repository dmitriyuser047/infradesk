CREATE TABLE incident (
  id              uuid        PRIMARY KEY,
  organization_id uuid        NOT NULL,
  monitor_rule_id uuid        NOT NULL,
  resource_id     uuid        NOT NULL,
  status          varchar(32) NOT NULL,
  started_at      timestamptz NOT NULL,
  opened_at       timestamptz NOT NULL,
  resolved_at     timestamptz,
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL,

  CONSTRAINT uq_incident_id_organization
    UNIQUE (id, organization_id),

  CONSTRAINT fk_incident_monitor_rule_tenant
    FOREIGN KEY (
      monitor_rule_id,
      organization_id
    )
    REFERENCES monitor_rule (
      id,
      organization_id
    ),

  CONSTRAINT fk_incident_resource_tenant
    FOREIGN KEY (
      resource_id,
      organization_id
    )
    REFERENCES resource (
      id,
      organization_id
    ),

  CONSTRAINT ck_incident_status
    CHECK (status IN ('OPEN', 'RESOLVED')),

  CONSTRAINT ck_incident_resolution
    CHECK (
      (status = 'OPEN' AND resolved_at IS NULL)
      OR
      (status = 'RESOLVED' AND resolved_at IS NOT NULL)
    ),

  CONSTRAINT ck_incident_started_before_opened
    CHECK (started_at <= opened_at),

  CONSTRAINT ck_incident_resolved_after_opened
    CHECK (resolved_at IS NULL OR resolved_at >= opened_at)
);

CREATE UNIQUE INDEX ux_incident_open_monitor_rule
  ON incident (
    organization_id,
    monitor_rule_id
  )
  WHERE status = 'OPEN';
