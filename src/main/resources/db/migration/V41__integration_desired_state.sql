-- Stage 24D: persistent desired state for explicitly selected Remnawave nodes.
--
-- An integration is observed only (OBSERVE) unless its owner opts into managing selected nodes
-- (MANAGED_SELECTED). Every integration that exists before this release stays OBSERVE, so an
-- upgrade never starts automatic writes. Desired state is the intent; what Remnawave reports
-- stays in integration_inventory_object, and the only thing that ever writes to Remnawave is an
-- integration_action_execution.

ALTER TABLE integration ADD COLUMN management_mode varchar(32) NOT NULL DEFAULT 'OBSERVE'
  CONSTRAINT ck_integration_management_mode CHECK (management_mode IN ('OBSERVE', 'MANAGED_SELECTED'));
-- Desired state needs fresh observations: a managed integration is always automatically observed.
ALTER TABLE integration ADD CONSTRAINT ck_integration_managed_requires_sync
  CHECK (management_mode = 'OBSERVE' OR enabled);
ALTER TABLE integration ADD CONSTRAINT uq_integration_id_org_mode UNIQUE (id, organization_id, management_mode);

CREATE TABLE integration_desired_state (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  inventory_object_id uuid NOT NULL,
  -- Always MANAGED_SELECTED: with the foreign key below, an OBSERVE integration cannot have intents.
  management_mode varchar(32) NOT NULL DEFAULT 'MANAGED_SELECTED'
    CONSTRAINT ck_integration_desired_state_mode CHECK (management_mode = 'MANAGED_SELECTED'),
  desired_state varchar(16) NOT NULL CHECK (desired_state IN ('ENABLED', 'DISABLED')),
  -- Which intent an automatic action executed: bumped on every material change.
  version bigint NOT NULL CHECK (version >= 1),
  set_by_user_id uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  next_reconcile_at timestamptz NOT NULL,
  claimed_by uuid,
  claim_token uuid,
  claim_until timestamptz,
  -- The observation (the object's last_seen_at) the latest remediation was decided on. A new
  -- action needs a newer observation: at most one action per observation.
  last_attempt_observation_at timestamptz,
  -- Identity of that action; history outlives this row, so it is not a foreign key.
  last_action_execution_id uuid,
  CONSTRAINT uq_integration_desired_state_object UNIQUE (organization_id, integration_id, inventory_object_id),
  CONSTRAINT fk_integration_desired_state_integration FOREIGN KEY (integration_id, organization_id, management_mode)
    REFERENCES integration(id, organization_id, management_mode) ON DELETE CASCADE,
  CONSTRAINT fk_integration_desired_state_object FOREIGN KEY (inventory_object_id, integration_id, organization_id)
    REFERENCES integration_inventory_object(id, integration_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT ck_integration_desired_state_claim CHECK (
    (claim_token IS NULL AND claimed_by IS NULL AND claim_until IS NULL) OR
    (claim_token IS NOT NULL AND claimed_by IS NOT NULL AND claim_until IS NOT NULL))
);
CREATE INDEX ix_integration_desired_state_due ON integration_desired_state (next_reconcile_at, id);
CREATE INDEX ix_integration_desired_state_claim ON integration_desired_state (claim_token) WHERE claim_token IS NOT NULL;

-- Why an action exists: a person's one-shot request, or the reconciliation of a desired state.
ALTER TABLE integration_action_execution ADD COLUMN source varchar(16) NOT NULL DEFAULT 'MANUAL'
  CONSTRAINT ck_integration_action_source CHECK (source IN ('MANUAL', 'DESIRED_STATE'));
ALTER TABLE integration_action_execution ADD COLUMN desired_state_id_snapshot uuid;
ALTER TABLE integration_action_execution ADD COLUMN desired_state_version_snapshot bigint;
ALTER TABLE integration_action_execution ADD CONSTRAINT ck_integration_action_source_snapshot CHECK (
  (source = 'MANUAL' AND desired_state_id_snapshot IS NULL AND desired_state_version_snapshot IS NULL) OR
  (source = 'DESIRED_STATE' AND desired_state_id_snapshot IS NOT NULL AND desired_state_version_snapshot IS NOT NULL
    AND action_code IN ('NODE_ENABLE', 'NODE_DISABLE')));
CREATE INDEX ix_integration_action_object
  ON integration_action_execution (organization_id, integration_id, inventory_object_id, finished_at DESC);

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
  'INTEGRATION_ACTION_REQUESTED',
  'INTEGRATION_MANAGEMENT_MODE_CHANGED','INTEGRATION_DESIRED_STATE_SET','INTEGRATION_DESIRED_STATE_REMOVED'
));
