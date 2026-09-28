-- A rollout orchestrates immutable per-node deployment snapshots. Remote file bytes and credentials
-- never enter these tables. Pending items do not occupy a resource deployment slot.

CREATE TABLE configuration_rollout (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  request_id uuid NOT NULL,
  profile_id uuid NOT NULL,
  profile_revision_number integer NOT NULL CHECK (profile_revision_number >= 1),
  state varchar(20) NOT NULL CHECK (state IN
    ('QUEUED','RUNNING','PAUSED','FAILED','ROLLING_BACK','ROLLED_BACK','SUCCEEDED','CANCELLED')),
  canary_count integer NOT NULL CHECK (canary_count BETWEEN 0 AND 20),
  batch_size integer NOT NULL CHECK (batch_size BETWEEN 1 AND 20),
  pause_seconds integer NOT NULL CHECK (pause_seconds BETWEEN 0 AND 3600),
  stop_on_failure boolean NOT NULL,
  rollback_mode varchar(24) NOT NULL CHECK (rollback_mode IN ('FAILED_TARGET_ONLY','ALL_APPLIED')),
  lease_owner uuid,
  lease_token uuid,
  lease_expires_at timestamptz,
  cancel_requested boolean NOT NULL DEFAULT false,
  -- An operator asked to roll back every node this rollout applied, whatever its rollback mode.
  rollback_requested boolean NOT NULL DEFAULT false,
  created_by_user_id uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  started_at timestamptz,
  finished_at timestamptz,
  next_action_at timestamptz,
  last_paused_position integer NOT NULL DEFAULT -1,
  CONSTRAINT uq_configuration_rollout_id_organization UNIQUE (id, organization_id),
  CONSTRAINT uq_configuration_rollout_request UNIQUE (organization_id, request_id),
  CONSTRAINT fk_configuration_rollout_profile FOREIGN KEY (profile_id, organization_id)
    REFERENCES configuration_profile(id, organization_id),
  CONSTRAINT fk_configuration_rollout_revision FOREIGN KEY (profile_id, profile_revision_number)
    REFERENCES configuration_revision(profile_id, revision_number),
  CONSTRAINT ck_configuration_rollout_lease CHECK
    ((lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL) OR
     (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
);
CREATE INDEX ix_configuration_rollout_claim ON configuration_rollout(state, lease_expires_at, created_at, id)
  WHERE state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK');
CREATE INDEX ix_configuration_rollout_history ON configuration_rollout(organization_id, created_at DESC, id DESC);

CREATE TABLE configuration_rollout_item (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  rollout_id uuid NOT NULL,
  position integer NOT NULL CHECK (position >= 0),
  assignment_id uuid NOT NULL,
  assignment_version integer NOT NULL CHECK (assignment_version >= 1),
  resource_id uuid NOT NULL,
  target_path varchar(4096) NOT NULL,
  connection_id uuid NOT NULL,
  connection_updated_at timestamptz NOT NULL,
  desired_sha256 char(64) NOT NULL CHECK (desired_sha256 ~ '^[0-9a-f]{64}$'),
  expected_remote_sha256 char(64) CHECK (expected_remote_sha256 ~ '^[0-9a-f]{64}$'),
  remote_expected_missing boolean NOT NULL,
  activation varchar(24) NOT NULL CHECK (activation IN ('NONE','SYSTEMD_RELOAD','SYSTEMD_RESTART')),
  unit_name varchar(255),
  validator_executable varchar(1024),
  new_file_mode integer NOT NULL CHECK (new_file_mode BETWEEN 0 AND 511),
  state varchar(16) NOT NULL CHECK (state IN ('PENDING','DEPLOYING','SUCCEEDED','FAILED','ROLLED_BACK','SKIPPED')),
  deployment_id uuid,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT uq_configuration_rollout_item_order UNIQUE (rollout_id, position),
  CONSTRAINT uq_configuration_rollout_item_assignment UNIQUE (rollout_id, assignment_id),
  CONSTRAINT uq_configuration_rollout_item_id_organization UNIQUE (id, organization_id),
  CONSTRAINT fk_configuration_rollout_item_rollout FOREIGN KEY (rollout_id, organization_id)
    REFERENCES configuration_rollout(id, organization_id),
  CONSTRAINT fk_configuration_rollout_item_assignment FOREIGN KEY (assignment_id, organization_id)
    REFERENCES configuration_assignment(id, organization_id),
  CONSTRAINT fk_configuration_rollout_item_resource FOREIGN KEY (resource_id, organization_id)
    REFERENCES resource(id, organization_id),
  CONSTRAINT fk_configuration_rollout_item_connection FOREIGN KEY (connection_id, organization_id)
    REFERENCES connection(id, organization_id),
  CONSTRAINT fk_configuration_rollout_item_deployment FOREIGN KEY (deployment_id, organization_id)
    REFERENCES configuration_deployment(id, organization_id),
  CONSTRAINT ck_configuration_rollout_item_expected CHECK
    (remote_expected_missing = (expected_remote_sha256 IS NULL)),
  CONSTRAINT ck_configuration_rollout_item_unit CHECK
    ((activation = 'NONE' AND unit_name IS NULL) OR
     (activation <> 'NONE' AND unit_name ~ '^[A-Za-z0-9_.@:-]+\.service$'))
);
CREATE INDEX ix_configuration_rollout_item_state ON configuration_rollout_item(rollout_id, state, position);

CREATE TABLE configuration_rollout_item_value (
  item_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  name varchar(64) NOT NULL,
  value varchar(4096) NOT NULL,
  PRIMARY KEY(item_id, name),
  FOREIGN KEY(item_id, organization_id) REFERENCES configuration_rollout_item(id, organization_id)
);
CREATE TABLE configuration_rollout_item_validator_arg (
  item_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  position integer NOT NULL CHECK (position BETWEEN 0 AND 31),
  argument varchar(1024) NOT NULL,
  PRIMARY KEY(item_id, position),
  FOREIGN KEY(item_id, organization_id) REFERENCES configuration_rollout_item(id, organization_id)
);

ALTER TABLE configuration_deployment ADD CONSTRAINT fk_configuration_deployment_rollout
  FOREIGN KEY (rollout_id, organization_id) REFERENCES configuration_rollout(id, organization_id);
ALTER TABLE configuration_deployment ADD CONSTRAINT fk_configuration_deployment_rollout_item
  FOREIGN KEY (rollout_item_id, organization_id) REFERENCES configuration_rollout_item(id, organization_id);

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
  'CONFIGURATION_ASSIGNMENTS_PROMOTED','CONFIGURATION_ROLLOUT_REQUESTED','CONFIGURATION_ROLLOUT_CANCELLED'
));
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_target_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_target_type CHECK (target_type IN (
  'PROJECT','ENVIRONMENT','CONNECTION','MONITOR_RULE','RESOURCE','NOTIFICATION_CHANNEL','ACCOUNT',
  'TERMINAL_SESSION','CONFIGURATION_PROFILE','CONFIGURATION_ASSIGNMENT','CONFIGURATION_DEPLOYMENT',
  'CONFIGURATION_ROLLOUT'
));
