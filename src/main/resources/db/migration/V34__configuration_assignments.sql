-- Configuration assignments: the desired state of one file on one resource.
--
-- An assignment says "on this resource, this file should hold this exact revision of this profile,
-- rendered with these values". It pins a revision number, never "latest": a newer revision of the
-- profile changes nothing here until someone explicitly moves the assignment to it.
--
-- This is desired state only. Nothing here connects to a server, writes a file or runs a command;
-- delivering the state is a later stage. The target is a generic resource (the NODE-only rule is
-- the application's), and there is deliberately no connection, host or credential column: how a
-- file would be delivered is not part of what the file should be.

CREATE TABLE configuration_assignment (
  id                      uuid           PRIMARY KEY,
  organization_id         uuid           NOT NULL REFERENCES organization (id),
  resource_id             uuid           NOT NULL,
  profile_id              uuid           NOT NULL,
  profile_revision_number integer        NOT NULL,
  target_path             varchar(4096)  NOT NULL,
  -- Optimistic concurrency: every change is a compare-and-set on this number.
  version                 integer        NOT NULL DEFAULT 1,
  -- Removal is soft: history and later deployment records may still name the assignment.
  removed_at              timestamptz,
  created_at              timestamptz    NOT NULL,
  updated_at              timestamptz    NOT NULL,

  -- Target of the tenant-aware foreign key of a value.
  CONSTRAINT uq_configuration_assignment_id_organization UNIQUE (id, organization_id),

  -- The resource and the profile belong to the assignment's own organization.
  CONSTRAINT fk_configuration_assignment_resource_tenant
    FOREIGN KEY (resource_id, organization_id)
    REFERENCES resource (id, organization_id),
  CONSTRAINT fk_configuration_assignment_profile_tenant
    FOREIGN KEY (profile_id, organization_id)
    REFERENCES configuration_profile (id, organization_id),
  -- The pinned revision is a revision of that very profile; a revision's own tenant key ties it
  -- to the profile's organization, so the chain cannot cross tenants either.
  CONSTRAINT fk_configuration_assignment_revision
    FOREIGN KEY (profile_id, profile_revision_number)
    REFERENCES configuration_revision (profile_id, revision_number),

  CONSTRAINT ck_configuration_assignment_revision CHECK (profile_revision_number >= 1),
  CONSTRAINT ck_configuration_assignment_version CHECK (version >= 1),
  -- The application accepts only normalized absolute POSIX paths; the database refuses at least a
  -- relative path, a control character and a parent-directory segment.
  CONSTRAINT ck_configuration_assignment_target_path CHECK (
    target_path ~ '^/'
    AND target_path !~ '[[:cntrl:]]'
    AND target_path !~ '(^|/)\.\.(/|$)'
  ),
  CONSTRAINT ck_configuration_assignment_removed CHECK (removed_at IS NULL OR removed_at >= created_at)
);

-- One desired owner per file: two active assignments may never claim the same path on the same
-- resource. A removed assignment frees its path. Two concurrent claims meet here, and one loses.
CREATE UNIQUE INDEX ux_configuration_assignment_active_target
  ON configuration_assignment (organization_id, resource_id, target_path)
  WHERE removed_at IS NULL;

-- The lists: an organization's active assignments, newest first, whole or by profile.
CREATE INDEX ix_configuration_assignment_list
  ON configuration_assignment (organization_id, created_at DESC, id DESC)
  WHERE removed_at IS NULL;

CREATE INDEX ix_configuration_assignment_profile
  ON configuration_assignment (organization_id, profile_id, created_at DESC, id DESC)
  WHERE removed_at IS NULL;

-- Only the values a user gave. Defaults stay in the immutable revision and are never copied here,
-- so an inherited default is never mistaken for a deliberate override. A value is text; its type
-- is the one the pinned revision declares.
CREATE TABLE configuration_assignment_value (
  assignment_id   uuid          NOT NULL,
  organization_id uuid          NOT NULL,
  name            varchar(64)   NOT NULL,
  value           varchar(4096) NOT NULL,

  CONSTRAINT pk_configuration_assignment_value PRIMARY KEY (assignment_id, name),

  CONSTRAINT fk_configuration_assignment_value_assignment_tenant
    FOREIGN KEY (assignment_id, organization_id)
    REFERENCES configuration_assignment (id, organization_id),

  CONSTRAINT ck_configuration_assignment_value_name CHECK (name ~ '^[A-Za-z][A-Za-z0-9_]{0,63}$')
);

-- The journal learns the assignment lifecycle. Identifiers only: never a value, a template or
-- rendered content.
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
  'CONFIGURATION_ASSIGNMENT_CREATED','CONFIGURATION_ASSIGNMENT_UPDATED','CONFIGURATION_ASSIGNMENT_REMOVED'
));
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_target_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_target_type CHECK (target_type IN (
  'PROJECT','ENVIRONMENT','CONNECTION','MONITOR_RULE','RESOURCE','NOTIFICATION_CHANNEL','ACCOUNT',
  'TERMINAL_SESSION','CONFIGURATION_PROFILE','CONFIGURATION_ASSIGNMENT'
));
