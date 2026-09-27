CREATE TABLE terminal_session (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  connection_id uuid NOT NULL,
  actor_user_id uuid NOT NULL REFERENCES user_account(id),
  auth_session_id uuid NOT NULL REFERENCES auth_session(id),
  state varchar(16) NOT NULL CHECK (state IN ('OPENING','ACTIVE','REVOKED','CLOSED')),
  lease_owner uuid NOT NULL,
  lease_token uuid NOT NULL,
  lease_expires_at timestamptz NOT NULL,
  connection_updated_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  opened_at timestamptz,
  closed_at timestamptz,
  close_reason varchar(48),
  FOREIGN KEY (connection_id, organization_id) REFERENCES connection(id, organization_id),
  CHECK (lease_expires_at > created_at),
  CHECK (opened_at IS NULL OR opened_at >= created_at),
  CHECK (closed_at IS NULL OR closed_at >= created_at),
  CHECK ((state IN ('OPENING','ACTIVE') AND closed_at IS NULL AND close_reason IS NULL)
    OR (state IN ('CLOSED','REVOKED') AND closed_at IS NOT NULL AND close_reason IS NOT NULL)),
  CHECK (state <> 'ACTIVE' OR opened_at IS NOT NULL),
  CHECK (close_reason IN ('CLIENT_CLOSE','REMOTE_EOF','IDLE_TIMEOUT','MAX_LIFETIME',
    'SERVER_SHUTDOWN','LEASE_EXPIRED','SSH_OPEN_FAILED','SSH_FAILURE','PROTOCOL_ERROR',
    'AUTH_SESSION_ENDED','TERMINAL_PERMISSION_REVOKED','CONNECTION_CHANGED',
    'TERMINAL_SESSION_REVOKED','SESSION_VALIDATION_FAILED'))
);
CREATE INDEX ix_terminal_session_org_active ON terminal_session (organization_id, lease_expires_at)
  WHERE state IN ('OPENING','ACTIVE');
CREATE INDEX ix_terminal_session_actor_active ON terminal_session (organization_id, actor_user_id, lease_expires_at)
  WHERE state IN ('OPENING','ACTIVE');
CREATE INDEX ix_terminal_session_auth ON terminal_session (auth_session_id);
CREATE INDEX ix_terminal_session_connection ON terminal_session (organization_id, connection_id);
CREATE INDEX ix_terminal_session_expiry ON terminal_session (lease_expires_at, id)
  WHERE state IN ('OPENING','ACTIVE');

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
  'PROJECT_CREATED','ENVIRONMENT_CREATED','CONNECTION_CREATED','CONNECTION_UPDATED',
  'CONNECTION_DELETED','MONITOR_RULE_CREATED','MONITOR_RULE_UPDATED','MANUAL_SYNC_REQUESTED',
  'CONTAINER_START_REQUESTED','CONTAINER_STOP_REQUESTED','CONTAINER_RESTART_REQUESTED',
  'NOTIFICATION_CHANNEL_CREATED','NOTIFICATION_CHANNEL_UPDATED',
  'NOTIFICATION_CHANNEL_ENABLED','NOTIFICATION_CHANNEL_DISABLED',
  'ACCOUNT_PASSWORD_CHANGED','ACCOUNT_PROFILE_UPDATED',
  'ACCOUNT_SESSION_REVOKED','ACCOUNT_OTHER_SESSIONS_REVOKED','ACCOUNT_ALL_SESSIONS_REVOKED',
  'TERMINAL_SESSION_OPENED','TERMINAL_SESSION_CLOSED'
));
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_target_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_target_type CHECK (target_type IN (
  'PROJECT','ENVIRONMENT','CONNECTION','MONITOR_RULE','RESOURCE','NOTIFICATION_CHANNEL','ACCOUNT','TERMINAL_SESSION'
));
