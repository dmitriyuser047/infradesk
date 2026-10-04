-- Stage25D: declarative desired state for a group of Remnawave nodes, with drift detection.
-- Additive only. Nothing here applies anything: there is no execution state in this migration.

-- A named group inside one Remnawave integration. The desired pointer lives here, not inside a
-- revision, so promoting a revision is one metadata update under an optimistic version check.
CREATE TABLE remnawave_fleet (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  integration_id uuid NOT NULL,
  code varchar(64) NOT NULL CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
  name varchar(80) NOT NULL CHECK (length(btrim(name)) BETWEEN 2 AND 80),
  description varchar(400),
  desired_revision_id uuid,
  version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
  archived boolean NOT NULL DEFAULT false,
  created_by uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  UNIQUE (id, organization_id),
  UNIQUE (id, organization_id, integration_id),
  CONSTRAINT uq_remnawave_fleet_code UNIQUE (organization_id, integration_id, code),
  CONSTRAINT fk_remnawave_fleet_integration FOREIGN KEY (integration_id, organization_id)
    REFERENCES integration(id, organization_id) ON DELETE CASCADE
);
CREATE INDEX ix_remnawave_fleet_list ON remnawave_fleet (organization_id, integration_id, archived, name, id);

-- An immutable desired configuration. It references objects that are already versioned elsewhere
-- and holds no configuration body, no script and no secret.
CREATE TABLE remnawave_fleet_revision (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  fleet_id uuid NOT NULL,
  number integer NOT NULL CHECK (number >= 1),
  schema_version integer NOT NULL CHECK (schema_version = 1),
  content jsonb NOT NULL CHECK (jsonb_typeof(content) = 'object'),
  content_hash char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  server_profile_id uuid NOT NULL,
  server_profile_revision_id uuid NOT NULL,
  configuration_profile_id uuid NOT NULL,
  configuration_revision_id uuid NOT NULL,
  inventory_config_profile_id uuid NOT NULL,
  created_by uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  UNIQUE (id, organization_id),
  UNIQUE (id, fleet_id, organization_id),
  CONSTRAINT uq_remnawave_fleet_revision_number UNIQUE (fleet_id, number),
  CONSTRAINT fk_remnawave_fleet_revision_fleet FOREIGN KEY (fleet_id, organization_id)
    REFERENCES remnawave_fleet(id, organization_id) ON DELETE CASCADE,
  -- Every pinned object belongs to the same tenant as the fleet.
  CONSTRAINT fk_remnawave_fleet_revision_profile FOREIGN KEY (server_profile_id, organization_id)
    REFERENCES server_profile(id, organization_id),
  CONSTRAINT fk_remnawave_fleet_revision_profile_revision FOREIGN KEY (server_profile_revision_id, organization_id)
    REFERENCES server_profile_revision(id, organization_id),
  CONSTRAINT fk_remnawave_fleet_revision_configuration FOREIGN KEY (configuration_profile_id, organization_id)
    REFERENCES configuration_profile(id, organization_id),
  CONSTRAINT fk_remnawave_fleet_revision_configuration_revision
    FOREIGN KEY (configuration_revision_id, organization_id)
    REFERENCES configuration_revision(id, organization_id)
);
CREATE INDEX ix_remnawave_fleet_revision_history
  ON remnawave_fleet_revision (organization_id, fleet_id, number DESC);

-- The desired pointer may only name a revision of its own fleet.
ALTER TABLE remnawave_fleet ADD CONSTRAINT fk_remnawave_fleet_desired
  FOREIGN KEY (desired_revision_id, id, organization_id)
  REFERENCES remnawave_fleet_revision(id, fleet_id, organization_id);

-- One node of one integration belongs to at most one active fleet: two owners would mean two
-- conflicting desired states. A removed membership stays readable as history.
CREATE TABLE remnawave_fleet_membership (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  fleet_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  inventory_node_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  removed_at timestamptz,
  -- Due and claim state of the read-only observer. It lives on the member, not on the derived
  -- assessment, so discarding every assessment never loses the schedule.
  next_check_at timestamptz NOT NULL,
  claimed_by uuid,
  claim_token uuid,
  claim_deadline timestamptz,
  CONSTRAINT ck_remnawave_fleet_membership_claim
    CHECK ((claimed_by IS NULL) = (claim_token IS NULL) AND (claim_token IS NULL) = (claim_deadline IS NULL)),
  UNIQUE (id, organization_id),
  UNIQUE (id, fleet_id, organization_id),
  CONSTRAINT fk_remnawave_fleet_membership_fleet FOREIGN KEY (fleet_id, organization_id, integration_id)
    REFERENCES remnawave_fleet(id, organization_id, integration_id) ON DELETE CASCADE,
  CONSTRAINT fk_remnawave_fleet_membership_node FOREIGN KEY (inventory_node_id, integration_id, organization_id)
    REFERENCES integration_inventory_object(id, integration_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_remnawave_fleet_membership_resource FOREIGN KEY (resource_id, organization_id)
    REFERENCES resource(id, organization_id)
);
CREATE UNIQUE INDEX uq_remnawave_fleet_membership_node
  ON remnawave_fleet_membership (organization_id, integration_id, inventory_node_id)
  WHERE removed_at IS NULL;
CREATE INDEX ix_remnawave_fleet_membership_fleet
  ON remnawave_fleet_membership (organization_id, fleet_id, removed_at, id);
CREATE INDEX ix_remnawave_fleet_membership_due
  ON remnawave_fleet_membership (next_check_at, claim_deadline) WHERE removed_at IS NULL;

-- A derived verdict, bound to the exact desired revision and membership version it was computed
-- for. It is a cache: dropping every row loses no desired state.
CREATE TABLE remnawave_fleet_node_assessment (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  fleet_id uuid NOT NULL,
  fleet_revision_id uuid NOT NULL,
  membership_id uuid NOT NULL,
  membership_version bigint NOT NULL CHECK (membership_version >= 1),
  inventory_node_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  compliance varchar(16) NOT NULL CHECK (compliance IN ('COMPLIANT','DRIFTED','UNKNOWN','BLOCKED')),
  health varchar(16) NOT NULL CHECK (health IN ('HEALTHY','DEGRADED','UNKNOWN')),
  drift_reasons text[] NOT NULL DEFAULT '{}',
  health_reasons text[] NOT NULL DEFAULT '{}',
  rollout_blockers text[] NOT NULL DEFAULT '{}',
  inventory_observed_at timestamptz,
  server_observed_at timestamptz,
  local_observed_at timestamptz,
  computed_at timestamptz NOT NULL,
  assessment_version integer NOT NULL CHECK (assessment_version >= 1),
  CONSTRAINT uq_remnawave_fleet_assessment_membership UNIQUE (membership_id),
  CONSTRAINT fk_remnawave_fleet_assessment_membership FOREIGN KEY (membership_id, fleet_id, organization_id)
    REFERENCES remnawave_fleet_membership(id, fleet_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_remnawave_fleet_assessment_revision FOREIGN KEY (fleet_revision_id, fleet_id, organization_id)
    REFERENCES remnawave_fleet_revision(id, fleet_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT ck_remnawave_fleet_assessment_version CHECK (assessment_version >= 1)
);
CREATE INDEX ix_remnawave_fleet_assessment_fleet
  ON remnawave_fleet_node_assessment (organization_id, fleet_id, compliance, health);

-- A revision is a historical fact. The desired pointer moves; the revision never changes.
CREATE FUNCTION infradesk_fleet_revision_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'REMNAWAVE_FLEET_REVISION_IMMUTABLE';
END $$;
CREATE TRIGGER remnawave_fleet_revision_immutable BEFORE UPDATE OR DELETE ON remnawave_fleet_revision
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_revision_immutable();

-- A member must be a node that is actually bound to the resource the membership names, and both
-- must belong to the fleet's own integration and tenant.
CREATE FUNCTION infradesk_fleet_membership_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.removed_at IS NOT NULL THEN RETURN NEW; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM integration_resource_binding b
    WHERE b.organization_id = NEW.organization_id AND b.integration_id = NEW.integration_id
      AND b.inventory_object_id = NEW.inventory_node_id AND b.resource_id = NEW.resource_id
  ) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_BINDING_REQUIRED' USING ERRCODE = '23514';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM integration_inventory_object o
    WHERE o.id = NEW.inventory_node_id AND o.organization_id = NEW.organization_id
      AND o.integration_id = NEW.integration_id AND o.object_type = 'NODE'
  ) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_NODE_REQUIRED' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_fleet_membership_binding
  BEFORE INSERT OR UPDATE OF inventory_node_id, resource_id, removed_at ON remnawave_fleet_membership
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_membership_binding();

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
  'SERVER_PROFILE_ASSIGNED','SERVER_PROFILE_UNASSIGNED','SERVER_PROFILE_ARCHIVED','SERVER_PROFILE_APPLY_REQUESTED',
  'REMNAWAVE_NODE_ONBOARDING_REQUESTED','REMNAWAVE_NODE_ONBOARDING_COMPLETED',
  'REMNAWAVE_FLEET_CREATED','REMNAWAVE_FLEET_UPDATED','REMNAWAVE_FLEET_ARCHIVED',
  'REMNAWAVE_FLEET_REVISION_CREATED','REMNAWAVE_FLEET_REVISION_PROMOTED',
  'REMNAWAVE_FLEET_MEMBER_ADDED','REMNAWAVE_FLEET_MEMBER_REMOVED'
));
