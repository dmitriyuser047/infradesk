-- Stage 24F: durable, profile-wide Remnawave config rollout orchestration.
ALTER TABLE integration_config_deployment
  ADD COLUMN source varchar(24) NOT NULL DEFAULT 'MANUAL'
    CONSTRAINT ck_integration_config_deployment_source
      CHECK (source IN ('MANUAL','ROLLOUT_TARGET','ROLLOUT_ROLLBACK')),
  ADD COLUMN rollout_id uuid;

CREATE TABLE integration_config_rollout (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  inventory_object_id uuid NOT NULL,
  binding_id uuid NOT NULL,
  configuration_profile_id uuid NOT NULL,
  baseline_revision_id uuid,
  baseline_revision_number integer CHECK (baseline_revision_number >= 1),
  baseline_sha256 varchar(64) CHECK (baseline_sha256 ~ '^[0-9a-f]{64}$'),
  target_revision_id uuid NOT NULL,
  target_revision_number integer NOT NULL CHECK (target_revision_number >= 1),
  target_sha256 varchar(64) NOT NULL CHECK (target_sha256 ~ '^[0-9a-f]{64}$'),
  request_id uuid NOT NULL,
  requested_by_user_id uuid NOT NULL REFERENCES user_account(id),
  automatic_rollback boolean NOT NULL,
  affected_node_count integer NOT NULL DEFAULT 0 CHECK (affected_node_count >= 0),
  baseline_healthy_node_count integer NOT NULL DEFAULT 0 CHECK (baseline_healthy_node_count >= 0),
  preexisting_unhealthy_node_count integer NOT NULL DEFAULT 0 CHECK (preexisting_unhealthy_node_count >= 0),
  status varchar(24) NOT NULL CHECK (status IN (
    'PREPARING','APPLYING','VERIFYING','ROLLBACK_APPLYING','ROLLBACK_VERIFYING',
    'SUCCEEDED','ROLLED_BACK','FAILED','UNKNOWN','CANCELLED')),
  baseline_sync_session_id uuid,
  target_verification_sync_session_id uuid,
  rollback_verification_sync_session_id uuid,
  target_deployment_id uuid,
  rollback_deployment_id uuid,
  verification_deadline_at timestamptz,
  claimed_by uuid,
  claim_token uuid,
  claim_until timestamptz,
  error_code varchar(64),
  error_message varchar(255),
  created_at timestamptz NOT NULL,
  started_at timestamptz,
  finished_at timestamptz,
  updated_at timestamptz NOT NULL,
  CONSTRAINT uq_integration_config_rollout_request UNIQUE (organization_id, request_id),
  CONSTRAINT uq_integration_config_rollout_scope UNIQUE (id, organization_id),
  CONSTRAINT fk_integration_config_rollout_binding FOREIGN KEY (binding_id, integration_id, organization_id)
    REFERENCES integration_config_profile_binding (id, integration_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_integration_config_rollout_object FOREIGN KEY (inventory_object_id, integration_id, organization_id)
    REFERENCES integration_inventory_object (id, integration_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_integration_config_rollout_target_revision FOREIGN KEY
    (target_revision_id, configuration_profile_id, organization_id)
    REFERENCES configuration_revision (id, profile_id, organization_id),
  CONSTRAINT fk_integration_config_rollout_baseline_revision FOREIGN KEY
    (baseline_revision_id, configuration_profile_id, organization_id)
    REFERENCES configuration_revision (id, profile_id, organization_id),
  CONSTRAINT ck_integration_config_rollout_claim CHECK (
    (claim_token IS NULL AND claimed_by IS NULL AND claim_until IS NULL) OR
    (claim_token IS NOT NULL AND claimed_by IS NOT NULL AND claim_until IS NOT NULL))
);
CREATE UNIQUE INDEX ux_integration_config_rollout_active
  ON integration_config_rollout (organization_id, integration_id, inventory_object_id)
  WHERE status IN ('PREPARING','APPLYING','VERIFYING','ROLLBACK_APPLYING','ROLLBACK_VERIFYING');
CREATE INDEX ix_integration_config_rollout_due
  ON integration_config_rollout (updated_at, id)
  WHERE status IN ('PREPARING','APPLYING','VERIFYING','ROLLBACK_APPLYING','ROLLBACK_VERIFYING');
CREATE INDEX ix_integration_config_rollout_history
  ON integration_config_rollout (organization_id, integration_id, inventory_object_id, created_at DESC, id DESC);

ALTER TABLE integration_config_deployment
  ADD CONSTRAINT fk_integration_config_deployment_rollout
  FOREIGN KEY (rollout_id, organization_id) REFERENCES integration_config_rollout(id, organization_id);
CREATE UNIQUE INDEX ux_integration_config_deployment_rollout_source
  ON integration_config_deployment (rollout_id, source) WHERE rollout_id IS NOT NULL;

CREATE TABLE integration_config_rollout_node_baseline (
  rollout_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  inventory_object_id uuid NOT NULL,
  external_id_snapshot varchar(128) NOT NULL,
  display_name_snapshot varchar(255) NOT NULL,
  was_disabled boolean NOT NULL,
  was_connected boolean NOT NULL,
  was_connecting boolean NOT NULL,
  active_config_profile_uuid_snapshot varchar(128),
  observed_at timestamptz NOT NULL,
  PRIMARY KEY (rollout_id, inventory_object_id),
  CONSTRAINT fk_integration_config_rollout_node_rollout FOREIGN KEY (rollout_id, organization_id)
    REFERENCES integration_config_rollout(id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_integration_config_rollout_node_object FOREIGN KEY
    (inventory_object_id, integration_id, organization_id)
    REFERENCES integration_inventory_object(id, integration_id, organization_id) ON DELETE CASCADE
);

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
  'INTEGRATION_ACTION_REQUESTED','INTEGRATION_MANAGEMENT_MODE_CHANGED',
  'INTEGRATION_DESIRED_STATE_SET','INTEGRATION_DESIRED_STATE_REMOVED',
  'INTEGRATION_CONFIG_PROFILE_ADOPTED','INTEGRATION_CONFIG_REVISION_CREATED',
  'INTEGRATION_CONFIG_DEPLOYMENT_REQUESTED','INTEGRATION_CONFIG_ROLLOUT_REQUESTED',
  'INTEGRATION_CONFIG_ROLLOUT_CANCELLED'
));
