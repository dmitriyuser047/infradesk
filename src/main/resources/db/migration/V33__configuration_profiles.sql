-- Configuration profiles: reusable desired-configuration definitions owned by an organization.
--
-- A profile holds metadata only. Its content lives in revisions, and a revision never changes
-- after it is written: a later deployment that says "profile v7" must mean the same content
-- forever. New content is always a new revision, numbered under a row lock on the profile.
-- Nothing here is assigned to servers or executed anywhere; that belongs to later stages.

CREATE TABLE configuration_profile (
  id                     uuid          PRIMARY KEY,
  organization_id        uuid          NOT NULL REFERENCES organization (id),
  code                   varchar(64)   NOT NULL,
  name                   varchar(255)  NOT NULL,
  description            varchar(4000),
  archived               boolean       NOT NULL DEFAULT false,
  latest_revision_number integer       NOT NULL,
  created_at             timestamptz   NOT NULL,
  updated_at             timestamptz   NOT NULL,

  -- Target of the tenant-aware foreign key of a revision.
  CONSTRAINT uq_configuration_profile_id_organization UNIQUE (id, organization_id),
  -- Codes are unique per organization, not globally: two tenants may both have "vpn-default".
  CONSTRAINT uq_configuration_profile_organization_code UNIQUE (organization_id, code),

  CONSTRAINT ck_configuration_profile_code CHECK (code ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
  CONSTRAINT ck_configuration_profile_name CHECK (length(btrim(name)) BETWEEN 1 AND 255),
  CONSTRAINT ck_configuration_profile_latest_revision CHECK (latest_revision_number >= 1)
);

-- The list: an organization's active or archived profiles, by name.
CREATE INDEX ix_configuration_profile_list
  ON configuration_profile (organization_id, archived, name, id);

CREATE TABLE configuration_revision (
  id                 uuid        PRIMARY KEY,
  organization_id    uuid        NOT NULL,
  profile_id         uuid        NOT NULL,
  revision_number    integer     NOT NULL,
  template_text      text        NOT NULL,
  created_by_user_id uuid        NOT NULL REFERENCES user_account (id),
  created_at         timestamptz NOT NULL,

  CONSTRAINT uq_configuration_revision_id_organization UNIQUE (id, organization_id),
  -- The version numbers of a profile are unique; the history reads them through this index.
  CONSTRAINT uq_configuration_revision_profile_number UNIQUE (profile_id, revision_number),

  -- A revision belongs to a profile of the same tenant.
  CONSTRAINT fk_configuration_revision_profile_tenant
    FOREIGN KEY (profile_id, organization_id)
    REFERENCES configuration_profile (id, organization_id),

  CONSTRAINT ck_configuration_revision_number CHECK (revision_number >= 1),
  CONSTRAINT ck_configuration_revision_template_size CHECK (octet_length(template_text) <= 262144)
);

CREATE TABLE configuration_revision_variable (
  revision_id     uuid          NOT NULL,
  organization_id uuid          NOT NULL,
  name            varchar(64)   NOT NULL,
  value_type      varchar(16)   NOT NULL,
  required        boolean       NOT NULL,
  default_value   varchar(4096),
  description     varchar(1000),
  position        integer       NOT NULL,

  -- Names are case-sensitive, and unique within a revision.
  CONSTRAINT pk_configuration_revision_variable PRIMARY KEY (revision_id, name),
  CONSTRAINT uq_configuration_revision_variable_position UNIQUE (revision_id, position),

  CONSTRAINT fk_configuration_revision_variable_revision_tenant
    FOREIGN KEY (revision_id, organization_id)
    REFERENCES configuration_revision (id, organization_id),

  CONSTRAINT ck_configuration_revision_variable_name CHECK (name ~ '^[A-Za-z][A-Za-z0-9_]{0,63}$'),
  -- No secret-like types: a value stored here is never encrypted and is shown to its readers.
  CONSTRAINT ck_configuration_revision_variable_type CHECK (value_type IN ('STRING', 'INTEGER', 'BOOLEAN')),
  CONSTRAINT ck_configuration_revision_variable_position CHECK (position >= 0)
);

-- Revisions are immutable, and not only by convention: the database refuses to change one.
CREATE FUNCTION configuration_revision_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'configuration revisions are immutable'
    USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_configuration_revision_immutable
  BEFORE UPDATE ON configuration_revision
  FOR EACH ROW EXECUTE FUNCTION configuration_revision_immutable();

CREATE TRIGGER tr_configuration_revision_variable_immutable
  BEFORE UPDATE ON configuration_revision_variable
  FOR EACH ROW EXECUTE FUNCTION configuration_revision_immutable();

-- The journal learns the profile lifecycle. It keeps identifiers only: never a template, a
-- variable, a default value or rendered content.
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
  'CONFIGURATION_PROFILE_ARCHIVED','CONFIGURATION_REVISION_CREATED'
));
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_target_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_target_type CHECK (target_type IN (
  'PROJECT','ENVIRONMENT','CONNECTION','MONITOR_RULE','RESOURCE','NOTIFICATION_CHANNEL','ACCOUNT',
  'TERMINAL_SESSION','CONFIGURATION_PROFILE'
));
