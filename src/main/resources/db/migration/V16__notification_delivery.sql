-- Transactional outbox for incident lifecycle notifications. Rows are written in the same
-- transaction as the incident change they report; delivery happens afterwards, outside any
-- business transaction.

CREATE TABLE notification_delivery (
  id               uuid        PRIMARY KEY,
  organization_id  uuid        NOT NULL,

  incident_id      uuid        NOT NULL,
  resource_id      uuid        NOT NULL,
  monitor_rule_id  uuid        NOT NULL,

  event_type       varchar(32) NOT NULL,
  reason           varchar(32) NOT NULL,
  channel          varchar(32) NOT NULL,

  -- When the incident changed, as opposed to when this row was written.
  occurred_at      timestamptz NOT NULL,

  status           varchar(32) NOT NULL,
  attempt_count    bigint      NOT NULL,
  next_attempt_at  timestamptz NOT NULL,

  claimed_by       uuid,
  claimed_until    timestamptz,

  sent_at          timestamptz,
  last_error_code  varchar(64),

  created_at       timestamptz NOT NULL,
  updated_at       timestamptz NOT NULL,

  CONSTRAINT uq_notification_delivery_id_organization
    UNIQUE (id, organization_id),

  CONSTRAINT fk_notification_delivery_incident_tenant
    FOREIGN KEY (incident_id, organization_id)
    REFERENCES incident (id, organization_id),

  CONSTRAINT fk_notification_delivery_resource_tenant
    FOREIGN KEY (resource_id, organization_id)
    REFERENCES resource (id, organization_id),

  CONSTRAINT fk_notification_delivery_monitor_rule_tenant
    FOREIGN KEY (monitor_rule_id, organization_id)
    REFERENCES monitor_rule (id, organization_id),

  CONSTRAINT ck_notification_delivery_event_type
    CHECK (event_type IN ('INCIDENT_OPENED', 'INCIDENT_RESOLVED')),

  CONSTRAINT ck_notification_delivery_reason
    CHECK (reason IN ('THRESHOLD', 'NO_DATA')),

  CONSTRAINT ck_notification_delivery_channel
    CHECK (channel IN ('WEBHOOK')),

  CONSTRAINT ck_notification_delivery_status
    CHECK (status IN ('PENDING', 'SENT', 'DEAD')),

  CONSTRAINT ck_notification_delivery_attempt_count
    CHECK (attempt_count >= 0),

  CONSTRAINT ck_notification_delivery_claim_pair
    CHECK (
      (claimed_by IS NULL AND claimed_until IS NULL)
      OR (claimed_by IS NOT NULL AND claimed_until IS NOT NULL)
    ),

  -- Only a delivered event carries a delivery time.
  CONSTRAINT ck_notification_delivery_sent_at
    CHECK (
      (status = 'SENT' AND sent_at IS NOT NULL)
      OR (status IN ('PENDING', 'DEAD') AND sent_at IS NULL)
    ),

  -- A finished delivery holds no claim.
  CONSTRAINT ck_notification_delivery_finished_claim
    CHECK (status = 'PENDING' OR claimed_by IS NULL)
);

-- One event per incident lifecycle transition per channel: recording the same transition twice
-- must not produce a second webhook.
CREATE UNIQUE INDEX ux_notification_delivery_event
  ON notification_delivery (
    organization_id,
    incident_id,
    event_type,
    channel
  );

-- The dispatcher claim path: pending rows whose attempt is due and whose lease expired.
CREATE INDEX ix_notification_delivery_claimable
  ON notification_delivery (
    next_attempt_at,
    claimed_until,
    created_at,
    id
  )
  WHERE status = 'PENDING';
