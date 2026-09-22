CREATE TABLE monitor_rule (
  id              uuid        PRIMARY KEY,
  organization_id uuid        NOT NULL,
  resource_id     uuid        NOT NULL,
  metric_code     varchar(64) NOT NULL,
  operator        varchar(32) NOT NULL,
  threshold       numeric     NOT NULL,
  for_seconds     bigint      NOT NULL,
  enabled         boolean     NOT NULL,
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL,

  CONSTRAINT uq_monitor_rule_id_organization
    UNIQUE (id, organization_id),

  CONSTRAINT fk_monitor_rule_resource_tenant
    FOREIGN KEY (
      resource_id,
      organization_id
    )
    REFERENCES resource (
      id,
      organization_id
    ),

  CONSTRAINT ck_monitor_rule_for_seconds_non_negative
    CHECK (for_seconds >= 0),

  CONSTRAINT ck_monitor_rule_operator
    CHECK (operator IN ('GREATER_THAN')),

  CONSTRAINT ck_monitor_rule_metric_code
    CHECK (metric_code IN ('CPU_USAGE_PERCENT', 'MEMORY_USAGE_PERCENT'))
);

CREATE TABLE monitor_rule_state (
  organization_id uuid        NOT NULL,
  monitor_rule_id uuid        NOT NULL,
  status          varchar(32) NOT NULL,
  pending_since   timestamptz,
  updated_at      timestamptz NOT NULL,

  CONSTRAINT pk_monitor_rule_state
    PRIMARY KEY (
      organization_id,
      monitor_rule_id
    ),

  CONSTRAINT fk_monitor_rule_state_rule_tenant
    FOREIGN KEY (
      monitor_rule_id,
      organization_id
    )
    REFERENCES monitor_rule (
      id,
      organization_id
    ),

  CONSTRAINT ck_monitor_rule_state_status
    CHECK (status IN ('OK', 'PENDING', 'FIRING')),

  CONSTRAINT ck_monitor_rule_state_pending_since
    CHECK (
      (status = 'OK' AND pending_since IS NULL)
      OR
      (status IN ('PENDING', 'FIRING') AND pending_since IS NOT NULL)
    )
);
