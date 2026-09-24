-- Append-only operational journal. The rows point at the tables that own the state; nothing is
-- ever rebuilt from them, and they are never updated or deleted by the application.

CREATE TABLE history_event (
  id                     uuid        PRIMARY KEY,
  organization_id        uuid        NOT NULL REFERENCES organization (id),

  event_type             varchar(64) NOT NULL,
  source                 varchar(32) NOT NULL,

  -- Historical references without foreign keys: an entry has to outlive the resource, the
  -- connection, the incident or the execution it describes.
  resource_id            uuid,
  connection_id          uuid,
  incident_id            uuid,
  operation_execution_id uuid,
  sync_session_id        uuid,
  actor_user_id          uuid,

  -- When the fact happened, as opposed to when the row was written.
  occurred_at            timestamptz NOT NULL,
  created_at             timestamptz NOT NULL,

  CONSTRAINT ck_history_event_type
    CHECK (event_type IN (
      'RESOURCE_DISCOVERED',
      'RESOURCE_DEACTIVATED',
      'INCIDENT_OPENED',
      'INCIDENT_RESOLVED',
      'OPERATION_REQUESTED',
      'OPERATION_SUCCEEDED',
      'OPERATION_FAILED',
      'OPERATION_UNKNOWN',
      'SYNC_FAILED'
    )),

  CONSTRAINT ck_history_event_source
    CHECK (source IN ('USER', 'SYSTEM')),

  -- A person acted, or the runtime did; there is no third case in this version.
  CONSTRAINT ck_history_event_actor
    CHECK (
      (source = 'USER' AND actor_user_id IS NOT NULL)
      OR (source = 'SYSTEM' AND actor_user_id IS NULL)
    )
);

-- The organization timeline.
CREATE INDEX ix_history_event_organization_recent
  ON history_event (organization_id, occurred_at DESC, id DESC);

-- The timeline of one resource, which is what the resource page asks for.
CREATE INDEX ix_history_event_resource_recent
  ON history_event (organization_id, resource_id, occurred_at DESC, id DESC)
  WHERE resource_id IS NOT NULL;
