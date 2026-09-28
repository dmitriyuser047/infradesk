-- A deployment is an immutable approval snapshot, separate from desired assignment state.
-- No credential, rendered body, remote body, diff, or command output is persisted here.
CREATE TABLE configuration_deployment (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  request_id uuid NOT NULL,
  assignment_id uuid NOT NULL,
  assignment_version integer NOT NULL CHECK (assignment_version >= 1),
  resource_id uuid NOT NULL,
  profile_id uuid NOT NULL,
  profile_revision_number integer NOT NULL CHECK (profile_revision_number >= 1),
  target_path varchar(4096) NOT NULL,
  connection_id uuid NOT NULL,
  connection_updated_at timestamptz NOT NULL,
  desired_sha256 char(64) NOT NULL CHECK (desired_sha256 ~ '^[0-9a-f]{64}$'),
  expected_remote_sha256 char(64) CHECK (expected_remote_sha256 ~ '^[0-9a-f]{64}$'),
  remote_expected_missing boolean NOT NULL,
  activation varchar(24) NOT NULL CHECK (activation IN ('NONE','SYSTEMD_RELOAD','SYSTEMD_RESTART')),
  unit_name varchar(255),
  validator_executable varchar(1024),
  -- Validator arguments are ordered rows in configuration_deployment_validator_arg.
  new_file_mode integer NOT NULL DEFAULT 420 CHECK (new_file_mode BETWEEN 0 AND 511),
  state varchar(20) NOT NULL CHECK (state IN
    ('QUEUED','RUNNING','SUCCEEDED','FAILED','ROLLED_BACK','ROLLBACK_FAILED','CANCELLED')),
  phase varchar(16) NOT NULL CHECK (phase IN
    ('PRECHECK','UPLOAD','VALIDATE','REPLACE','ACTIVATE','VERIFY','CLEANUP','ROLLBACK')),
  -- The phase in which the failure that started a rollback happened; it decides whether the
  -- service has to be activated again once the previous file is back.
  rollback_from_phase varchar(16) CHECK (rollback_from_phase IN
    ('PRECHECK','UPLOAD','VALIDATE','REPLACE','ACTIVATE','VERIFY','CLEANUP')),
  -- Bounded retries after a transient SSH failure; the deployment continues from its phase.
  transient_attempts integer NOT NULL DEFAULT 0 CHECK (transient_attempts >= 0),
  -- A rollout keeps each applied node's backup until the rollout ends, so it can still roll back.
  backup_retained boolean NOT NULL DEFAULT false,
  backup_cleanup_attempts integer NOT NULL DEFAULT 0 CHECK (backup_cleanup_attempts >= 0),
  backup_cleanup_not_before timestamptz,
  lease_owner uuid,
  lease_token uuid,
  lease_expires_at timestamptz,
  created_by_user_id uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  started_at timestamptz,
  finished_at timestamptz,
  failure_code varchar(80),
  cancel_requested boolean NOT NULL DEFAULT false,
  retry_of_deployment_id uuid,
  rollout_id uuid,
  rollout_item_id uuid,
  CONSTRAINT uq_configuration_deployment_id_organization UNIQUE (id, organization_id),
  CONSTRAINT uq_configuration_deployment_request UNIQUE (organization_id, request_id),
  CONSTRAINT fk_configuration_deployment_assignment_tenant FOREIGN KEY (assignment_id, organization_id)
    REFERENCES configuration_assignment(id, organization_id),
  CONSTRAINT fk_configuration_deployment_resource_tenant FOREIGN KEY (resource_id, organization_id)
    REFERENCES resource(id, organization_id),
  CONSTRAINT fk_configuration_deployment_profile_tenant FOREIGN KEY (profile_id, organization_id)
    REFERENCES configuration_profile(id, organization_id),
  CONSTRAINT fk_configuration_deployment_revision FOREIGN KEY (profile_id, profile_revision_number)
    REFERENCES configuration_revision(profile_id, revision_number),
  CONSTRAINT fk_configuration_deployment_connection_tenant FOREIGN KEY (connection_id, organization_id)
    REFERENCES connection(id, organization_id),
  CONSTRAINT ck_configuration_deployment_expected CHECK
    (remote_expected_missing = (expected_remote_sha256 IS NULL)),
  CONSTRAINT ck_configuration_deployment_unit CHECK
    ((activation = 'NONE' AND unit_name IS NULL) OR
     (activation <> 'NONE' AND unit_name ~ '^[A-Za-z0-9_.@:-]+\.service$')),
  CONSTRAINT ck_configuration_deployment_lease CHECK
    ((lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL) OR
     (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
  CONSTRAINT fk_configuration_deployment_retry_tenant FOREIGN KEY (retry_of_deployment_id, organization_id)
    REFERENCES configuration_deployment(id, organization_id),
  -- Only a running deployment holds a lease, and only a finished one has a finish time.
  CONSTRAINT ck_configuration_deployment_lease_state CHECK ((state = 'RUNNING') = (lease_token IS NOT NULL)),
  CONSTRAINT ck_configuration_deployment_finished CHECK
    ((state IN ('QUEUED','RUNNING')) = (finished_at IS NULL)),
  CONSTRAINT ck_configuration_deployment_rollback_origin CHECK
    ((phase = 'ROLLBACK') = (rollback_from_phase IS NOT NULL)),
  CONSTRAINT ck_configuration_deployment_rollout_pair CHECK ((rollout_id IS NULL) = (rollout_item_id IS NULL)),
  CONSTRAINT ck_configuration_deployment_backup CHECK (NOT backup_retained OR rollout_id IS NOT NULL),
  CONSTRAINT ck_configuration_deployment_target_path CHECK
    (target_path ~ '^/' AND target_path !~ '[[:cntrl:]]' AND target_path !~ '(^|/)\.\.(/|$)')
);

-- Durable per-resource serialization includes queued work, not just workers holding a lease.
CREATE UNIQUE INDEX ux_configuration_deployment_active_resource
  ON configuration_deployment(organization_id, resource_id)
  WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX ix_configuration_deployment_claim
  ON configuration_deployment(state, lease_expires_at, created_at, id)
  WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX ix_configuration_deployment_history
  ON configuration_deployment(organization_id, created_at DESC, id DESC);
CREATE INDEX ix_configuration_deployment_profile_history
  ON configuration_deployment(organization_id, profile_id, created_at DESC, id DESC);
CREATE INDEX ix_configuration_deployment_resource_history
  ON configuration_deployment(organization_id, resource_id, created_at DESC, id DESC);
-- The last successful deployment of each assignment, read for a whole page in one statement.
CREATE INDEX ix_configuration_deployment_assignment_success
  ON configuration_deployment(organization_id, assignment_id, finished_at DESC, id DESC)
  WHERE state = 'SUCCEEDED';
CREATE INDEX ix_configuration_deployment_assignment_active
  ON configuration_deployment(organization_id, assignment_id)
  WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX ix_configuration_deployment_backup_cleanup
  ON configuration_deployment(backup_cleanup_not_before, id)
  WHERE backup_retained;

CREATE TABLE configuration_deployment_value (
  deployment_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  name varchar(64) NOT NULL CHECK (name ~ '^[A-Za-z][A-Za-z0-9_]{0,63}$'),
  value varchar(4096) NOT NULL,
  PRIMARY KEY(deployment_id, name),
  FOREIGN KEY(deployment_id, organization_id)
    REFERENCES configuration_deployment(id, organization_id)
);

CREATE TABLE configuration_deployment_validator_arg (
  deployment_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  position integer NOT NULL CHECK (position BETWEEN 0 AND 31),
  argument varchar(1024) NOT NULL,
  PRIMARY KEY(deployment_id, position),
  FOREIGN KEY(deployment_id, organization_id)
    REFERENCES configuration_deployment(id, organization_id)
);

CREATE TABLE configuration_deployment_event (
  deployment_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  sequence integer NOT NULL CHECK (sequence >= 1),
  event_type varchar(40) NOT NULL CHECK (event_type IN
    ('QUEUED','CLAIMED','REMOTE_PRECHECK_OK','UPLOADED','VALIDATED','REPLACED','ACTIVATED',
     'VERIFIED','ROLLBACK_STARTED','ROLLBACK_SUCCEEDED','ROLLBACK_FAILED','SUCCEEDED','FAILED','CANCELLED',
     'CANCEL_REQUESTED','RETRY_SCHEDULED','RELEASED','BACKUP_RETAINED','BACKUP_CLEANED','BACKUP_CLEANUP_FAILED')),
  occurred_at timestamptz NOT NULL,
  PRIMARY KEY(deployment_id, sequence),
  FOREIGN KEY(deployment_id, organization_id)
    REFERENCES configuration_deployment(id, organization_id)
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
  'CONFIGURATION_DEPLOYMENT_REQUESTED','CONFIGURATION_DEPLOYMENT_CANCELLED'
));
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_target_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_target_type CHECK (target_type IN (
  'PROJECT','ENVIRONMENT','CONNECTION','MONITOR_RULE','RESOURCE','NOTIFICATION_CHANNEL','ACCOUNT',
  'TERMINAL_SESSION','CONFIGURATION_PROFILE','CONFIGURATION_ASSIGNMENT','CONFIGURATION_DEPLOYMENT'
));
