-- Stage 22E: resource labels and rules that keep configuration assignments present on every
-- matching node. Rules manage desired state only: nothing here deploys, reads or writes a server.

-- Labels are metadata: one value per key and resource, never executed and never a secret store.
CREATE TABLE resource_label (
  organization_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  key varchar(63) NOT NULL CHECK (key ~ '^[a-z][a-z0-9_.-]{0,62}$'),
  value varchar(128) NOT NULL CHECK (char_length(value) BETWEEN 1 AND 128 AND value !~ '[[:cntrl:]]'),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (resource_id, key),
  CONSTRAINT fk_resource_label_resource_tenant FOREIGN KEY (resource_id, organization_id)
    REFERENCES resource (id, organization_id)
);
-- Selectors look labels up by key and value within a tenant.
CREATE INDEX ix_resource_label_selector ON resource_label (organization_id, key, value, resource_id);

-- The whole label set of a resource changes by compare-and-set on this version.
CREATE TABLE resource_label_state (
  organization_id uuid NOT NULL,
  resource_id uuid PRIMARY KEY,
  version integer NOT NULL CHECK (version >= 1),
  updated_at timestamptz NOT NULL,
  CONSTRAINT fk_resource_label_state_resource_tenant FOREIGN KEY (resource_id, organization_id)
    REFERENCES resource (id, organization_id)
);

CREATE TABLE configuration_assignment_rule (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization (id),
  code varchar(64) NOT NULL CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$'),
  name varchar(255) NOT NULL CHECK (char_length(btrim(name)) >= 1),
  description varchar(2000),
  profile_id uuid NOT NULL,
  -- Always an exact revision; a newer one is adopted only by an explicit promotion.
  profile_revision_number integer NOT NULL CHECK (profile_revision_number >= 1),
  -- Immutable after creation: a different file is a different rule.
  target_path varchar(4096) NOT NULL,
  enabled boolean NOT NULL DEFAULT false,
  archived boolean NOT NULL DEFAULT false,
  version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by_user_id uuid NOT NULL REFERENCES user_account (id),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  last_reconciled_at timestamptz,
  next_reconcile_at timestamptz,
  lease_owner uuid,
  lease_token uuid,
  lease_expires_at timestamptz,
  CONSTRAINT uq_configuration_assignment_rule_id_organization UNIQUE (id, organization_id),
  CONSTRAINT uq_configuration_assignment_rule_code UNIQUE (organization_id, code),
  CONSTRAINT fk_configuration_assignment_rule_profile_tenant FOREIGN KEY (profile_id, organization_id)
    REFERENCES configuration_profile (id, organization_id),
  CONSTRAINT fk_configuration_assignment_rule_revision FOREIGN KEY (profile_id, profile_revision_number)
    REFERENCES configuration_revision (profile_id, revision_number),
  CONSTRAINT ck_configuration_assignment_rule_lease CHECK
    ((lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL) OR
     (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
  CONSTRAINT ck_configuration_assignment_rule_archived CHECK (NOT (archived AND enabled)),
  CONSTRAINT ck_configuration_assignment_rule_target_path CHECK
    (target_path ~ '^/' AND target_path !~ '[[:cntrl:]]' AND target_path !~ '(^|/)\.\.?(/|$)' AND target_path !~ '/$')
);
CREATE INDEX ix_configuration_assignment_rule_claim
  ON configuration_assignment_rule (next_reconcile_at, lease_expires_at, id)
  WHERE enabled AND NOT archived;
CREATE INDEX ix_configuration_assignment_rule_profile
  ON configuration_assignment_rule (organization_id, profile_id, created_at, id);

-- Selector: normalized, never an expression. Empty project/environment lists mean "any".
CREATE TABLE configuration_assignment_rule_project (
  rule_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  project_id uuid NOT NULL,
  PRIMARY KEY (rule_id, project_id),
  FOREIGN KEY (rule_id, organization_id) REFERENCES configuration_assignment_rule (id, organization_id),
  FOREIGN KEY (project_id, organization_id) REFERENCES project (id, organization_id)
);
CREATE TABLE configuration_assignment_rule_environment (
  rule_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  environment_id uuid NOT NULL,
  PRIMARY KEY (rule_id, environment_id),
  FOREIGN KEY (rule_id, organization_id) REFERENCES configuration_assignment_rule (id, organization_id),
  FOREIGN KEY (environment_id, organization_id) REFERENCES environment (id, organization_id)
);
-- REQUIRED labels must all be present; any matching EXCLUDED label rules a resource out.
CREATE TABLE configuration_assignment_rule_label (
  rule_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  kind varchar(16) NOT NULL CHECK (kind IN ('REQUIRED','EXCLUDED')),
  key varchar(63) NOT NULL CHECK (key ~ '^[a-z][a-z0-9_.-]{0,62}$'),
  value varchar(128) NOT NULL CHECK (char_length(value) BETWEEN 1 AND 128 AND value !~ '[[:cntrl:]]'),
  PRIMARY KEY (rule_id, kind, key, value),
  FOREIGN KEY (rule_id, organization_id) REFERENCES configuration_assignment_rule (id, organization_id)
);

-- A resource an operator took out of a rule: the rule never creates an assignment for it.
CREATE TABLE configuration_assignment_rule_exclusion (
  rule_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  created_by_user_id uuid NOT NULL REFERENCES user_account (id),
  created_at timestamptz NOT NULL,
  PRIMARY KEY (rule_id, resource_id),
  FOREIGN KEY (rule_id, organization_id) REFERENCES configuration_assignment_rule (id, organization_id),
  FOREIGN KEY (resource_id, organization_id) REFERENCES resource (id, organization_id)
);

-- The current reason a matching resource has no assignment of the rule. Current state only; no
-- value, template or rendered text is ever stored here.
CREATE TABLE configuration_assignment_rule_issue (
  rule_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  issue_code varchar(40) NOT NULL CHECK (issue_code IN
    ('NEEDS_VALUES','TARGET_PATH_CONFLICT','OTHER_RULE_CONFLICT','PROFILE_ARCHIVED','ASSIGNMENT_INVALID')),
  variable_name varchar(64),
  conflicting_assignment_id uuid,
  observed_at timestamptz NOT NULL,
  PRIMARY KEY (rule_id, resource_id),
  FOREIGN KEY (rule_id, organization_id) REFERENCES configuration_assignment_rule (id, organization_id),
  FOREIGN KEY (resource_id, organization_id) REFERENCES resource (id, organization_id),
  FOREIGN KEY (conflicting_assignment_id, organization_id) REFERENCES configuration_assignment (id, organization_id)
);
CREATE INDEX ix_configuration_assignment_rule_issue_rule ON configuration_assignment_rule_issue (organization_id, rule_id);

-- Provenance and management: an assignment created or adopted by a rule names it.
ALTER TABLE configuration_assignment ADD COLUMN source_rule_id uuid;
ALTER TABLE configuration_assignment ADD CONSTRAINT fk_configuration_assignment_source_rule
  FOREIGN KEY (source_rule_id, organization_id) REFERENCES configuration_assignment_rule (id, organization_id);
CREATE INDEX ix_configuration_assignment_source_rule
  ON configuration_assignment (organization_id, source_rule_id, id) WHERE removed_at IS NULL AND source_rule_id IS NOT NULL;

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
  'CONFIGURATION_ASSIGNMENT_ADOPTED','CONFIGURATION_ASSIGNMENT_DETACHED','RESOURCE_LABELS_UPDATED'
));
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_target_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_target_type CHECK (target_type IN (
  'PROJECT','ENVIRONMENT','CONNECTION','MONITOR_RULE','RESOURCE','NOTIFICATION_CHANNEL','ACCOUNT',
  'TERMINAL_SESSION','CONFIGURATION_PROFILE','CONFIGURATION_ASSIGNMENT','CONFIGURATION_DEPLOYMENT',
  'CONFIGURATION_ROLLOUT','CONFIGURATION_ASSIGNMENT_RULE'
));
