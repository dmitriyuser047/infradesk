CREATE TABLE provisioning_run (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  resource_id uuid NOT NULL,
  run_kind varchar(32) NOT NULL CHECK (run_kind IN ('SERVER_BASELINE_CHECK')),
  connection_id uuid NOT NULL,
  connection_updated_at timestamptz NOT NULL,
  resource_type varchar(64) NOT NULL,
  resource_kind varchar(64) NOT NULL,
  schema_version integer NOT NULL CHECK (schema_version = 1),
  steps jsonb NOT NULL CHECK (steps = '["PREFLIGHT","VERIFY"]'::jsonb),
  request_id uuid,
  requested_by_user_id uuid REFERENCES user_account(id),
  current_step varchar(16) CHECK (current_step IN ('PREFLIGHT','VERIFY')),
  status varchar(16) NOT NULL CHECK (status IN ('PLANNED','QUEUED','RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  started_at timestamptz,
  finished_at timestamptz,
  failure_code varchar(64),
  safe_message varchar(255),
  claimed_by uuid,
  claim_token uuid,
  claim_until timestamptz,
  CONSTRAINT fk_provisioning_resource_tenant FOREIGN KEY (resource_id, organization_id)
    REFERENCES resource(id, organization_id),
  CONSTRAINT fk_provisioning_connection_tenant FOREIGN KEY (connection_id, organization_id)
    REFERENCES connection(id, organization_id),
  CONSTRAINT ck_provisioning_claim CHECK ((status = 'RUNNING') =
    (claimed_by IS NOT NULL AND claim_token IS NOT NULL AND claim_until IS NOT NULL)),
  CONSTRAINT ck_provisioning_request CHECK ((status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','UNKNOWN')) =
    (request_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_provisioning_request ON provisioning_run(organization_id, request_id) WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_provisioning_active_resource ON provisioning_run(organization_id, resource_id)
  WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ix_provisioning_claim ON provisioning_run(status, created_at) WHERE status = 'QUEUED';
CREATE INDEX ix_provisioning_resource_history ON provisioning_run(organization_id, resource_id, created_at DESC, id DESC);

CREATE TABLE provisioning_run_step (
  id uuid NOT NULL UNIQUE,
  run_id uuid NOT NULL REFERENCES provisioning_run(id) ON DELETE CASCADE,
  step_kind varchar(16) NOT NULL CHECK (step_kind IN ('PREFLIGHT','VERIFY')),
  position smallint NOT NULL CHECK (position IN (0,1)),
  attempt integer NOT NULL DEFAULT 0 CHECK (attempt >= 0),
  status varchar(16) NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','SKIPPED','UNKNOWN')),
  started_at timestamptz,
  finished_at timestamptz,
  facts jsonb NOT NULL DEFAULT '{}'::jsonb,
  failure_code varchar(64),
  safe_message varchar(255),
  output_summary varchar(255),
  verification_result boolean,
  output_truncated boolean NOT NULL DEFAULT false,
  PRIMARY KEY (run_id, step_kind)
);

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
  'PROJECT_CREATED','ENVIRONMENT_CREATED','CONNECTION_CREATED','CONNECTION_UPDATED','CONNECTION_DELETED',
  'MONITOR_RULE_CREATED','MONITOR_RULE_UPDATED','MANUAL_SYNC_REQUESTED','CONTAINER_START_REQUESTED',
  'CONTAINER_STOP_REQUESTED','CONTAINER_RESTART_REQUESTED','NOTIFICATION_CHANNEL_CREATED',
  'NOTIFICATION_CHANNEL_UPDATED','NOTIFICATION_CHANNEL_ENABLED','NOTIFICATION_CHANNEL_DISABLED',
  'INTEGRATION_CREATED','INTEGRATION_UPDATED','INTEGRATION_ENABLED','INTEGRATION_DISABLED','INTEGRATION_DELETED',
  'INTEGRATION_TEST_REQUESTED','INTEGRATION_SYNC_REQUESTED','INTEGRATION_RESOURCE_BOUND',
  'INTEGRATION_RESOURCE_UNBOUND','INTEGRATION_ACTION_REQUESTED','INTEGRATION_MANAGEMENT_MODE_CHANGED',
  'INTEGRATION_DESIRED_STATE_SET','INTEGRATION_DESIRED_STATE_REMOVED','INTEGRATION_CONFIG_PROFILE_ADOPTED',
  'INTEGRATION_CONFIG_REVISION_CREATED','INTEGRATION_CONFIG_DEPLOYMENT_REQUESTED',
  'INTEGRATION_CONFIG_ROLLOUT_REQUESTED','INTEGRATION_CONFIG_ROLLOUT_CANCELLED','ACCOUNT_PASSWORD_CHANGED',
  'ACCOUNT_PROFILE_UPDATED','ACCOUNT_SESSION_REVOKED','ACCOUNT_OTHER_SESSIONS_REVOKED','ACCOUNT_ALL_SESSIONS_REVOKED',
  'TERMINAL_SESSION_OPENED','TERMINAL_SESSION_CLOSED','CONFIGURATION_PROFILE_CREATED','CONFIGURATION_PROFILE_UPDATED',
  'CONFIGURATION_PROFILE_ARCHIVED','CONFIGURATION_REVISION_CREATED','CONFIGURATION_ASSIGNMENT_CREATED',
  'CONFIGURATION_ASSIGNMENT_UPDATED','CONFIGURATION_ASSIGNMENT_REMOVED','CONFIGURATION_DEPLOYMENT_REQUESTED',
  'CONFIGURATION_DEPLOYMENT_CANCELLED','CONFIGURATION_ASSIGNMENTS_PROMOTED','CONFIGURATION_ROLLOUT_REQUESTED',
  'CONFIGURATION_ROLLOUT_CANCELLED','CONFIGURATION_RULE_CREATED','CONFIGURATION_RULE_UPDATED',
  'CONFIGURATION_RULE_ENABLED','CONFIGURATION_RULE_DISABLED','CONFIGURATION_RULE_ARCHIVED',
  'CONFIGURATION_RULE_REVISION_PROMOTED','CONFIGURATION_RULE_RESOURCE_EXCLUDED','CONFIGURATION_RULE_RESOURCE_INCLUDED',
  'CONFIGURATION_RULE_RECONCILE_REQUESTED','CONFIGURATION_ASSIGNMENT_ADOPTED','CONFIGURATION_ASSIGNMENT_DETACHED',
  'RESOURCE_LABELS_UPDATED','PROVISIONING_RUN_REQUESTED'
));
