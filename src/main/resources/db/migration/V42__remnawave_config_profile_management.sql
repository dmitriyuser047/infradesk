-- Stage 24E extends the existing revision history with encrypted Remnawave content.
-- Existing profiles and revisions remain file templates; raw Xray JSON never enters template_text.
ALTER TABLE configuration_profile ADD COLUMN kind varchar(32) NOT NULL DEFAULT 'FILE_TEMPLATE'
  CONSTRAINT ck_configuration_profile_kind CHECK (kind IN ('FILE_TEMPLATE', 'REMNAWAVE_CONFIG'));
ALTER TABLE configuration_profile ADD CONSTRAINT uq_configuration_profile_scope_kind
  UNIQUE (id, organization_id, kind);
ALTER TABLE configuration_revision ADD CONSTRAINT uq_configuration_revision_scope_profile
  UNIQUE (id, profile_id, organization_id);

CREATE TABLE configuration_revision_secure_payload (
  revision_id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  profile_id uuid NOT NULL,
  profile_kind varchar(32) NOT NULL DEFAULT 'REMNAWAVE_CONFIG'
    CONSTRAINT ck_configuration_secure_profile_kind CHECK (profile_kind = 'REMNAWAVE_CONFIG'),
  purpose varchar(64) NOT NULL
    CONSTRAINT ck_configuration_secure_purpose CHECK (purpose = 'infradesk/configuration/remnawave/v1'),
  nonce bytea NOT NULL CONSTRAINT ck_configuration_secure_nonce CHECK (octet_length(nonce) = 12),
  ciphertext bytea NOT NULL CONSTRAINT ck_configuration_secure_ciphertext CHECK (octet_length(ciphertext) BETWEEN 17 AND 262160),
  content_sha256 varchar(64) NOT NULL
    CONSTRAINT ck_configuration_secure_hash CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL,
  CONSTRAINT fk_configuration_secure_revision FOREIGN KEY (revision_id, profile_id, organization_id)
    REFERENCES configuration_revision (id, profile_id, organization_id),
  CONSTRAINT fk_configuration_secure_profile_kind FOREIGN KEY (profile_id, organization_id, profile_kind)
    REFERENCES configuration_profile (id, organization_id, kind)
);
CREATE TRIGGER tr_configuration_revision_secure_payload_immutable
  BEFORE UPDATE ON configuration_revision_secure_payload
  FOR EACH ROW EXECUTE FUNCTION configuration_revision_immutable();

CREATE TABLE integration_config_profile_binding (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  inventory_object_id uuid NOT NULL,
  configuration_profile_id uuid NOT NULL,
  profile_kind varchar(32) NOT NULL DEFAULT 'REMNAWAVE_CONFIG'
    CONSTRAINT ck_integration_config_binding_kind CHECK (profile_kind = 'REMNAWAVE_CONFIG'),
  created_by_user_id uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT uq_integration_config_binding_scope UNIQUE (id, integration_id, organization_id),
  CONSTRAINT uq_integration_config_binding_object UNIQUE (organization_id, integration_id, inventory_object_id),
  CONSTRAINT uq_integration_config_binding_profile UNIQUE (organization_id, configuration_profile_id),
  CONSTRAINT fk_integration_config_binding_object FOREIGN KEY (inventory_object_id, integration_id, organization_id)
    REFERENCES integration_inventory_object (id, integration_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_integration_config_binding_profile FOREIGN KEY (configuration_profile_id, organization_id, profile_kind)
    REFERENCES configuration_profile (id, organization_id, kind)
);

-- A request is a durable intent. Only the worker can issue the remote PATCH.
CREATE TABLE integration_config_deployment (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  inventory_object_id uuid NOT NULL,
  binding_id uuid NOT NULL,
  configuration_profile_id uuid NOT NULL,
  configuration_revision_id uuid NOT NULL,
  revision_number integer NOT NULL CHECK (revision_number >= 1),
  request_id uuid NOT NULL,
  requested_by_user_id uuid NOT NULL REFERENCES user_account(id),
  status varchar(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
  expected_remote_sha256 varchar(64) NOT NULL CHECK (expected_remote_sha256 ~ '^[0-9a-f]{64}$'),
  desired_sha256 varchar(64) NOT NULL CHECK (desired_sha256 ~ '^[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL,
  started_at timestamptz,
  recover_after_at timestamptz,
  finished_at timestamptz,
  claimed_by uuid,
  claim_token uuid,
  error_code varchar(64),
  error_message varchar(255),
  CONSTRAINT uq_integration_config_deployment_request UNIQUE (organization_id, request_id),
  CONSTRAINT fk_integration_config_deployment_binding FOREIGN KEY (binding_id, integration_id, organization_id)
    REFERENCES integration_config_profile_binding (id, integration_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_integration_config_deployment_object FOREIGN KEY (inventory_object_id, integration_id, organization_id)
    REFERENCES integration_inventory_object (id, integration_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_integration_config_deployment_revision FOREIGN KEY
    (configuration_revision_id, configuration_profile_id, organization_id)
    REFERENCES configuration_revision (id, profile_id, organization_id),
  CONSTRAINT ck_integration_config_deployment_lifecycle CHECK (
    (status = 'QUEUED' AND started_at IS NULL AND finished_at IS NULL AND claim_token IS NULL) OR
    (status = 'RUNNING' AND started_at IS NOT NULL AND finished_at IS NULL AND claim_token IS NOT NULL
      AND claimed_by IS NOT NULL AND recover_after_at IS NOT NULL) OR
    (status IN ('SUCCEEDED','FAILED','UNKNOWN') AND finished_at IS NOT NULL AND claim_token IS NULL)),
  CONSTRAINT ck_integration_config_deployment_error CHECK
    ((status IN ('QUEUED','RUNNING','SUCCEEDED') AND error_code IS NULL) OR
     (status IN ('FAILED','UNKNOWN') AND error_code IS NOT NULL))
);
CREATE UNIQUE INDEX ux_integration_config_deployment_active
  ON integration_config_deployment (organization_id, integration_id, inventory_object_id)
  WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ix_integration_config_deployment_queue
  ON integration_config_deployment (created_at, id) WHERE status = 'QUEUED';
CREATE INDEX ix_integration_config_deployment_history
  ON integration_config_deployment (organization_id, integration_id, inventory_object_id, created_at DESC, id DESC);

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
  'INTEGRATION_MANAGEMENT_MODE_CHANGED','INTEGRATION_DESIRED_STATE_SET','INTEGRATION_DESIRED_STATE_REMOVED',
  'INTEGRATION_CONFIG_PROFILE_ADOPTED','INTEGRATION_CONFIG_REVISION_CREATED',
  'INTEGRATION_CONFIG_DEPLOYMENT_REQUESTED'
));
