-- Durable journal of user configuration changes. Rows are written in the same transaction as the
-- business mutation they describe, and are never updated or deleted by the application.

CREATE TABLE audit_event (
  id              uuid        PRIMARY KEY,
  organization_id uuid        NOT NULL REFERENCES organization (id),
  actor_user_id   uuid        NOT NULL REFERENCES user_account (id),

  action          varchar(64) NOT NULL,
  target_type     varchar(32) NOT NULL,
  -- The target is polymorphic and the journal must outlive the object it describes, so this
  -- column carries no foreign key: a deleted connection keeps its CONNECTION_DELETED event.
  target_id       uuid,

  -- When the change happened, as opposed to when the row was written.
  occurred_at     timestamptz NOT NULL,
  created_at      timestamptz NOT NULL,

  CONSTRAINT ck_audit_event_action
    CHECK (action IN (
      'PROJECT_CREATED',
      'ENVIRONMENT_CREATED',
      'CONNECTION_CREATED',
      'CONNECTION_UPDATED',
      'CONNECTION_DELETED',
      'MONITOR_RULE_CREATED',
      'MONITOR_RULE_UPDATED',
      'MANUAL_SYNC_REQUESTED'
    )),

  CONSTRAINT ck_audit_event_target_type
    CHECK (target_type IN (
      'PROJECT',
      'ENVIRONMENT',
      'CONNECTION',
      'MONITOR_RULE'
    ))
);

-- The listing path: the newest events of one organization, paginated by (occurred_at, id).
CREATE INDEX ix_audit_event_organization_recent
  ON audit_event (
    organization_id,
    occurred_at DESC,
    id DESC
  );
