-- Explicit, one-shot writes to external inventory objects. No retry queue is involved.
CREATE TABLE integration_action_execution (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  inventory_object_id uuid NOT NULL,
  request_id uuid NOT NULL,
  action_code varchar(32) NOT NULL CHECK (action_code IN ('NODE_ENABLE','NODE_DISABLE','NODE_RESTART')),
  external_id_snapshot varchar(128) NOT NULL,
  display_name_snapshot varchar(255) NOT NULL,
  requested_by_user_id uuid NOT NULL REFERENCES user_account(id),
  status varchar(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
  created_at timestamptz NOT NULL,
  started_at timestamptz,
  recover_after_at timestamptz,
  finished_at timestamptz,
  claimed_by uuid,
  claim_token uuid,
  error_code varchar(64),
  error_message varchar(255),
  updated_at timestamptz NOT NULL,
  CONSTRAINT fk_integration_action_integration FOREIGN KEY (integration_id, organization_id)
    REFERENCES integration(id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_integration_action_object FOREIGN KEY (inventory_object_id, integration_id, organization_id)
    REFERENCES integration_inventory_object(id, integration_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT ck_integration_action_state CHECK (
    (status = 'QUEUED' AND started_at IS NULL AND recover_after_at IS NULL AND finished_at IS NULL
      AND claimed_by IS NULL AND claim_token IS NULL AND error_code IS NULL) OR
    (status = 'RUNNING' AND started_at IS NOT NULL AND recover_after_at IS NOT NULL AND finished_at IS NULL
      AND claimed_by IS NOT NULL AND claim_token IS NOT NULL AND error_code IS NULL) OR
    (status = 'SUCCEEDED' AND finished_at IS NOT NULL AND error_code IS NULL) OR
    (status IN ('FAILED','UNKNOWN') AND finished_at IS NOT NULL AND error_code IS NOT NULL)
  )
);
CREATE UNIQUE INDEX ux_integration_action_request ON integration_action_execution (organization_id, request_id);
CREATE UNIQUE INDEX ux_integration_action_active_object
  ON integration_action_execution (organization_id, integration_id, inventory_object_id)
  WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ix_integration_action_history
  ON integration_action_execution (organization_id, integration_id, created_at DESC, id DESC);
CREATE INDEX ix_integration_action_queue ON integration_action_execution (status, created_at, id);

-- A nudge made while a synchronization owns the schedule must survive its fenced completion.
ALTER TABLE integration_sync_state ADD COLUMN action_nudge_at timestamptz;

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
  'PROJECT_CREATED','ENVIRONMENT_CREATED','CONNECTION_CREATED','CONNECTION_UPDATED',
  'CONNECTION_DELETED','MONITOR_RULE_CREATED','MONITOR_RULE_UPDATED','MANUAL_SYNC_REQUESTED',
  'CONTAINER_START_REQUESTED','CONTAINER_STOP_REQUESTED','CONTAINER_RESTART_REQUESTED',
  'NOTIFICATION_CHANNEL_CREATED','NOTIFICATION_CHANNEL_UPDATED',
  'NOTIFICATION_CHANNEL_ENABLED','NOTIFICATION_CHANNEL_DISABLED',
  'ACCOUNT_PASSWORD_CHANGED','ACCOUNT_PROFILE_UPDATED',
  'ACCOUNT_SESSION_REVOKED','ACCOUNT_OTHER_SESSIONS_REVOKED','ACCOUNT_ALL_SESSIONS_REVOKED',
  'TERMINAL_SESSION_OPENED','TERMINAL_SESSION_CLOSED',
  'CONFIGURATION_PROFILE_CREATED','CONFIGURATION_PROFILE_UPDATED',
  'CONFIGURATION_PROFILE_ARCHIVED','CONFIGURATION_REVISION_CREATED',
  'CONFIGURATION_ASSIGNMENT_CREATED','CONFIGURATION_ASSIGNMENT_UPDATED','CONFIGURATION_ASSIGNMENT_REMOVED',
  'CONFIGURATION_DEPLOYMENT_REQUESTED','CONFIGURATION_DEPLOYMENT_CANCELLED',
  'CONFIGURATION_ASSIGNMENTS_PROMOTED','CONFIGURATION_ROLLOUT_REQUESTED','CONFIGURATION_ROLLOUT_CANCELLED',
  'CONFIGURATION_RULE_CREATED','CONFIGURATION_RULE_UPDATED','CONFIGURATION_RULE_ENABLED',
  'CONFIGURATION_RULE_DISABLED','CONFIGURATION_RULE_ARCHIVED','CONFIGURATION_RULE_REVISION_PROMOTED',
  'CONFIGURATION_RULE_RESOURCE_EXCLUDED','CONFIGURATION_RULE_RESOURCE_INCLUDED',
  'CONFIGURATION_RULE_RECONCILE_REQUESTED',
  'CONFIGURATION_ASSIGNMENT_ADOPTED','CONFIGURATION_ASSIGNMENT_DETACHED','RESOURCE_LABELS_UPDATED',
  'INTEGRATION_CREATED','INTEGRATION_UPDATED','INTEGRATION_ENABLED','INTEGRATION_DISABLED',
  'INTEGRATION_DELETED','INTEGRATION_TEST_REQUESTED',
  'INTEGRATION_SYNC_REQUESTED','INTEGRATION_RESOURCE_BOUND','INTEGRATION_RESOURCE_UNBOUND',
  'INTEGRATION_ACTION_REQUESTED'
));
