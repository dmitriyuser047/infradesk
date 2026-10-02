-- Versioned, tenant-scoped desired state. Revisions are immutable; assignments pin a revision.
CREATE TABLE server_profile (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  code varchar(64) NOT NULL CHECK (code ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
  name varchar(255) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 255),
  description varchar(4000),
  archived boolean NOT NULL DEFAULT false,
  latest_revision integer NOT NULL CHECK (latest_revision >= 1),
  created_by_user_id uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  UNIQUE (id, organization_id),
  UNIQUE (organization_id, code)
);

CREATE TABLE server_profile_revision (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  profile_id uuid NOT NULL,
  revision_number integer NOT NULL CHECK (revision_number >= 1),
  schema_version integer NOT NULL CHECK (schema_version = 1),
  content jsonb NOT NULL CHECK (jsonb_typeof(content) = 'object'),
  content_hash char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  created_by_user_id uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  UNIQUE (profile_id, revision_number),
  UNIQUE (id, profile_id, organization_id, revision_number),
  UNIQUE (id, organization_id),
  FOREIGN KEY (profile_id, organization_id) REFERENCES server_profile(id, organization_id)
);
CREATE INDEX ix_server_profile_revision_history ON server_profile_revision(profile_id, revision_number DESC);
CREATE FUNCTION reject_server_profile_revision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'server profile revisions are immutable';
END $$;
CREATE TRIGGER trg_server_profile_revision_immutable BEFORE UPDATE OR DELETE ON server_profile_revision
  FOR EACH ROW EXECUTE FUNCTION reject_server_profile_revision_mutation();

CREATE TABLE server_profile_assignment (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  profile_id uuid NOT NULL,
  revision_id uuid NOT NULL,
  revision_number integer NOT NULL,
  version bigint NOT NULL CHECK (version >= 1),
  assigned_by_user_id uuid NOT NULL REFERENCES user_account(id),
  assigned_at timestamptz NOT NULL,
  UNIQUE (organization_id, resource_id),
  UNIQUE (id, organization_id, resource_id, version, revision_id),
  FOREIGN KEY (resource_id, organization_id) REFERENCES resource(id, organization_id),
  FOREIGN KEY (profile_id, organization_id) REFERENCES server_profile(id, organization_id),
  FOREIGN KEY (revision_id, profile_id, organization_id, revision_number)
    REFERENCES server_profile_revision(id, profile_id, organization_id, revision_number)
);
CREATE INDEX ix_server_profile_assignment_profile ON server_profile_assignment(organization_id, profile_id, revision_number);

ALTER TABLE provisioning_run ADD CONSTRAINT uq_provisioning_run_tenant_resource UNIQUE (id, organization_id, resource_id);

-- Only the latest sanitized observation is retained. It is explicitly linked to assignment/source
-- identity and may be linked to the run whose independent VERIFY produced it.
CREATE TABLE server_profile_observation (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  source_connection_id uuid NOT NULL,
  source_updated_at timestamptz NOT NULL,
  assignment_id uuid,
  assignment_version bigint,
  revision_id uuid,
  schema_version integer NOT NULL CHECK (schema_version = 1),
  content jsonb NOT NULL CHECK (jsonb_typeof(content) = 'object'),
  content_hash char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  observed_at timestamptz NOT NULL,
  verified_run_id uuid,
  UNIQUE (organization_id, resource_id),
  CHECK ((assignment_id IS NULL AND assignment_version IS NULL AND revision_id IS NULL) OR
    (assignment_id IS NOT NULL AND assignment_version IS NOT NULL AND assignment_version > 0 AND revision_id IS NOT NULL)),
  FOREIGN KEY (resource_id, organization_id) REFERENCES resource(id, organization_id),
  FOREIGN KEY (source_connection_id, organization_id) REFERENCES connection(id, organization_id),
  FOREIGN KEY (revision_id, organization_id) REFERENCES server_profile_revision(id, organization_id),
  FOREIGN KEY (verified_run_id, organization_id, resource_id)
    REFERENCES provisioning_run(id, organization_id, resource_id)
);

ALTER TABLE provisioning_run ADD COLUMN profile_apply_snapshot jsonb;
ALTER TABLE provisioning_run ADD CONSTRAINT ck_provisioning_profile_snapshot CHECK (
  (run_kind = 'SERVER_BASELINE_CHECK' AND profile_apply_snapshot IS NULL) OR
  (run_kind = 'SERVER_PROFILE_APPLY' AND profile_apply_snapshot IS NOT NULL AND jsonb_typeof(profile_apply_snapshot) = 'object')
);
ALTER TABLE provisioning_run DROP CONSTRAINT provisioning_run_run_kind_check;
ALTER TABLE provisioning_run ADD CONSTRAINT provisioning_run_run_kind_check
  CHECK (run_kind IN ('SERVER_BASELINE_CHECK','SERVER_PROFILE_APPLY'));
ALTER TABLE provisioning_run DROP CONSTRAINT provisioning_run_steps_check;
ALTER TABLE provisioning_run ADD CONSTRAINT provisioning_run_steps_check CHECK (
  (run_kind = 'SERVER_BASELINE_CHECK' AND steps = '["PREFLIGHT","VERIFY"]'::jsonb) OR
  (run_kind = 'SERVER_PROFILE_APPLY' AND steps = '["PREFLIGHT","INSTALL_PACKAGES","CONFIGURE_NETWORK","CONFIGURE_LIMITS","CONFIGURE_FIREWALL","CONFIGURE_FAIL2BAN","CONFIGURE_DOCKER","DEPLOY_SITE","CONFIGURE_CADDY","VERIFY"]'::jsonb)
);
ALTER TABLE provisioning_run ALTER COLUMN current_step TYPE varchar(64);
ALTER TABLE provisioning_run DROP CONSTRAINT provisioning_run_current_step_check;
ALTER TABLE provisioning_run ADD CONSTRAINT provisioning_run_current_step_check CHECK (current_step IS NULL OR current_step IN (
  'PREFLIGHT','INSTALL_PACKAGES','CONFIGURE_NETWORK','CONFIGURE_LIMITS','CONFIGURE_FIREWALL',
  'CONFIGURE_FAIL2BAN','CONFIGURE_DOCKER','DEPLOY_SITE','CONFIGURE_CADDY','VERIFY'));

ALTER TABLE provisioning_run_step DROP CONSTRAINT provisioning_run_step_step_kind_check;
ALTER TABLE provisioning_run_step ALTER COLUMN step_kind TYPE varchar(64);
ALTER TABLE provisioning_run_step ADD CONSTRAINT provisioning_run_step_step_kind_check CHECK (step_kind IN (
  'PREFLIGHT','INSTALL_PACKAGES','CONFIGURE_NETWORK','CONFIGURE_LIMITS','CONFIGURE_FIREWALL',
  'CONFIGURE_FAIL2BAN','CONFIGURE_DOCKER','DEPLOY_SITE','CONFIGURE_CADDY','VERIFY'));
ALTER TABLE provisioning_run_step DROP CONSTRAINT provisioning_run_step_position_check;
ALTER TABLE provisioning_run_step ADD CONSTRAINT provisioning_run_step_position_check CHECK (position BETWEEN 0 AND 15);
ALTER TABLE provisioning_run_step ADD CONSTRAINT uq_provisioning_step_position UNIQUE (run_id, position);

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
  'RESOURCE_LABELS_UPDATED','PROVISIONING_RUN_REQUESTED','SERVER_PROFILE_CREATED','SERVER_PROFILE_REVISION_CREATED',
  'SERVER_PROFILE_ASSIGNED','SERVER_PROFILE_UNASSIGNED','SERVER_PROFILE_ARCHIVED','SERVER_PROFILE_APPLY_REQUESTED'
));
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_target_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_target_type CHECK (target_type IN (
  'PROJECT','ENVIRONMENT','CONNECTION','RESOURCE','MONITOR_RULE','NOTIFICATION_CHANNEL','INTEGRATION','ACCOUNT',
  'TERMINAL_SESSION','CONFIGURATION_PROFILE','CONFIGURATION_ASSIGNMENT','CONFIGURATION_DEPLOYMENT',
  'CONFIGURATION_ROLLOUT','CONFIGURATION_ASSIGNMENT_RULE','SERVER_PROFILE'
));
