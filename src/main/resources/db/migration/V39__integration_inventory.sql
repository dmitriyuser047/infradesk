-- Stage 24B: what an external control plane (Remnawave) reports, observed read-only.
--
-- An inventory object is the provider's own object (a node, a host, a config profile), never an
-- InfraDesk resource. It may be bound by hand to one InfraDesk NODE. Objects are never deleted
-- when the provider stops returning them: they become inactive and keep their id and binding.
-- Every table belongs to exactly one integration of one organization and disappears with it.

CREATE TABLE integration_sync_session (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  trigger varchar(16) NOT NULL CHECK (trigger IN ('MANUAL', 'SCHEDULED')),
  requested_by_user_id uuid REFERENCES user_account(id),
  started_at timestamptz NOT NULL,
  recover_after_at timestamptz NOT NULL,
  finished_at timestamptz,
  status varchar(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
  error_code varchar(64),
  error_message varchar(255),
  nodes_count integer CHECK (nodes_count >= 0),
  hosts_count integer CHECK (hosts_count >= 0),
  config_profiles_count integer CHECK (config_profiles_count >= 0),
  deactivated_count integer CHECK (deactivated_count >= 0),
  -- Referenced with its integration, so nothing can point at another integration's session.
  CONSTRAINT uq_integration_sync_session_scope UNIQUE (id, integration_id, organization_id),
  CONSTRAINT fk_integration_sync_session_integration FOREIGN KEY (integration_id, organization_id)
    REFERENCES integration(id, organization_id) ON DELETE CASCADE,
  CONSTRAINT ck_integration_sync_session_state CHECK (
    (status = 'RUNNING' AND finished_at IS NULL AND error_code IS NULL) OR
    (status = 'COMPLETED' AND finished_at IS NOT NULL AND error_code IS NULL) OR
    (status = 'FAILED' AND finished_at IS NOT NULL AND error_code IS NOT NULL)),
  CONSTRAINT ck_integration_sync_session_manual CHECK (trigger = 'MANUAL' OR requested_by_user_id IS NULL)
);
-- One running synchronization per integration: the database, not a lock in memory, decides.
CREATE UNIQUE INDEX ux_integration_sync_session_running
  ON integration_sync_session (organization_id, integration_id) WHERE status = 'RUNNING';
CREATE INDEX ix_integration_sync_session_history
  ON integration_sync_session (organization_id, integration_id, started_at DESC, id DESC);

CREATE TABLE integration_inventory_object (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  object_type varchar(32) NOT NULL CHECK (object_type IN ('NODE', 'HOST', 'CONFIG_PROFILE')),
  external_id varchar(128) NOT NULL CHECK (length(external_id) BETWEEN 1 AND 128),
  display_name varchar(255) NOT NULL,
  -- A typed, sanitized projection written by the application; never a raw provider response.
  summary_version integer NOT NULL CHECK (summary_version >= 1),
  summary jsonb NOT NULL CHECK (jsonb_typeof(summary) = 'object'),
  is_active boolean NOT NULL,
  first_seen_at timestamptz NOT NULL,
  last_seen_at timestamptz NOT NULL,
  last_seen_sync_session_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT uq_integration_inventory_object_scope UNIQUE (id, integration_id, organization_id),
  CONSTRAINT uq_integration_inventory_object_identity
    UNIQUE (organization_id, integration_id, object_type, external_id),
  CONSTRAINT fk_integration_inventory_object_integration FOREIGN KEY (integration_id, organization_id)
    REFERENCES integration(id, organization_id) ON DELETE CASCADE,
  -- The session that last saw an object belongs to the object's own integration.
  CONSTRAINT fk_integration_inventory_object_session
    FOREIGN KEY (last_seen_sync_session_id, integration_id, organization_id)
    REFERENCES integration_sync_session(id, integration_id, organization_id)
);
CREATE INDEX ix_integration_inventory_object_list
  ON integration_inventory_object (organization_id, integration_id, object_type, display_name, id);

-- A manual link from one external NODE to one InfraDesk NODE. One binding per external object; a
-- resource may be referenced by several control planes, so it carries no uniqueness of its own.
CREATE TABLE integration_resource_binding (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  inventory_object_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  created_by_user_id uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT uq_integration_resource_binding_object UNIQUE (inventory_object_id),
  CONSTRAINT fk_integration_resource_binding_integration FOREIGN KEY (integration_id, organization_id)
    REFERENCES integration(id, organization_id) ON DELETE CASCADE,
  -- A binding belongs to the integration of the object it binds.
  CONSTRAINT fk_integration_resource_binding_object
    FOREIGN KEY (inventory_object_id, integration_id, organization_id)
    REFERENCES integration_inventory_object(id, integration_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_integration_resource_binding_resource FOREIGN KEY (resource_id, organization_id)
    REFERENCES resource(id, organization_id)
);
CREATE INDEX ix_integration_resource_binding_resource
  ON integration_resource_binding (organization_id, resource_id);

-- Durable automatic-synchronization schedule, claimed with a lease like connection schedules.
CREATE TABLE integration_sync_state (
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  next_run_at timestamptz NOT NULL,
  consecutive_failures bigint NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
  claim_token uuid,
  claimed_by uuid,
  claim_until timestamptz,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (organization_id, integration_id),
  CONSTRAINT fk_integration_sync_state_integration FOREIGN KEY (integration_id, organization_id)
    REFERENCES integration(id, organization_id) ON DELETE CASCADE,
  CONSTRAINT ck_integration_sync_state_claim CHECK (
    (claim_token IS NULL AND claimed_by IS NULL AND claim_until IS NULL) OR
    (claim_token IS NOT NULL AND claimed_by IS NOT NULL AND claim_until IS NOT NULL))
);
CREATE INDEX ix_integration_sync_state_due ON integration_sync_state (next_run_at);

-- Integrations that existed before this release get a schedule that is due now.
INSERT INTO integration_sync_state (organization_id, integration_id, next_run_at, updated_at)
SELECT organization_id, id, now(), now() FROM integration;

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
  'INTEGRATION_SYNC_REQUESTED','INTEGRATION_RESOURCE_BOUND','INTEGRATION_RESOURCE_UNBOUND'
));
