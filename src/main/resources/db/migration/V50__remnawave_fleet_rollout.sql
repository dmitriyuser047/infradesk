-- Stage25E: controlled canary/wave rollout of a Fleet desired revision.
-- Additive only. A rollout is orchestration: it holds an immutable plan, a lease, and a journal of
-- the exact child operations it started. It owns no configuration body, script or secret.

CREATE TABLE remnawave_fleet_rollout (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  fleet_id uuid NOT NULL,
  fleet_revision_id uuid NOT NULL,
  request_id uuid,
  state varchar(16) NOT NULL CHECK (state IN
    ('PLANNED','QUEUED','RUNNING','PAUSED','SUCCEEDED','FAILED','UNKNOWN','ROLLING_BACK','ROLLED_BACK')),
  phase varchar(32) NOT NULL CHECK (phase IN
    ('VALIDATE','PREPARE_SHARED_CONFIG','APPLY_SHARED_CONFIG','VERIFY_SHARED_CONFIG','PREPARE_CANARY',
     'APPLY_CANARY','VERIFY_CANARY','APPLY_WAVES','VERIFY_WAVE','FINAL_VERIFY','ROLLBACK','COMPLETE')),
  -- The immutable preview, without secrets. Never edited after the plan is written.
  input_snapshot jsonb NOT NULL CHECK (jsonb_typeof(input_snapshot) = 'object'),
  snapshot_hash char(64) NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
  current_wave integer NOT NULL DEFAULT 0 CHECK (current_wave >= 0),
  wave_count integer NOT NULL CHECK (wave_count >= 0),
  pause_after_canary boolean NOT NULL,
  automatic_rollback boolean NOT NULL,
  rollback_scope varchar(16) NOT NULL CHECK (rollback_scope IN ('CURRENT_WAVE','ALL_COMPLETED')),
  created_by uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  started_at timestamptz,
  finished_at timestamptz,
  failure_code varchar(128),
  safe_message varchar(300),
  rollback_incomplete boolean NOT NULL DEFAULT false,
  pause_reason varchar(32),
  pause_requested_at timestamptz,
  pause_requested_by uuid REFERENCES user_account(id),
  paused_at timestamptz,
  paused_by uuid REFERENCES user_account(id),
  rollback_requested_at timestamptz,
  rollback_requested_by uuid REFERENCES user_account(id),
  rollback_requested_scope varchar(16) CHECK (rollback_requested_scope IN ('CURRENT_WAVE','ALL_COMPLETED')),
  next_run_at timestamptz NOT NULL,
  phase_started_at timestamptz NOT NULL,
  claim_owner uuid,
  claim_token uuid,
  claim_deadline timestamptz,
  version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_fleet_rollout_claim
    CHECK ((claim_owner IS NULL) = (claim_token IS NULL) AND (claim_token IS NULL) = (claim_deadline IS NULL)),
  CONSTRAINT ck_fleet_rollout_request CHECK (state = 'PLANNED' OR request_id IS NOT NULL OR finished_at IS NOT NULL),
  UNIQUE (id, organization_id),
  UNIQUE (id, fleet_id, organization_id),
  CONSTRAINT fk_fleet_rollout_fleet FOREIGN KEY (fleet_id, organization_id, integration_id)
    REFERENCES remnawave_fleet(id, organization_id, integration_id) ON DELETE CASCADE,
  CONSTRAINT fk_fleet_rollout_revision FOREIGN KEY (fleet_revision_id, fleet_id, organization_id)
    REFERENCES remnawave_fleet_revision(id, fleet_id, organization_id)
);
-- Double click and retry: the same request is the same rollout.
CREATE UNIQUE INDEX uq_fleet_rollout_request ON remnawave_fleet_rollout (organization_id, request_id)
  WHERE request_id IS NOT NULL;
-- At most one active rollout per fleet, guaranteed by the database.
CREATE UNIQUE INDEX uq_fleet_rollout_active_fleet ON remnawave_fleet_rollout (fleet_id)
  WHERE state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK');
CREATE INDEX ix_fleet_rollout_history ON remnawave_fleet_rollout (organization_id, fleet_id, created_at DESC, id);
CREATE INDEX ix_fleet_rollout_due ON remnawave_fleet_rollout (next_run_at, claim_deadline)
  WHERE state IN ('QUEUED','RUNNING','ROLLING_BACK');
CREATE INDEX ix_fleet_rollout_expiry ON remnawave_fleet_rollout (expires_at) WHERE state = 'PLANNED';

CREATE TABLE remnawave_fleet_rollout_member (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  rollout_id uuid NOT NULL,
  fleet_id uuid NOT NULL,
  membership_id uuid NOT NULL,
  membership_version bigint NOT NULL CHECK (membership_version >= 1),
  inventory_node_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  external_node_id varchar(128),
  -- Wave 0 is the canary. Position is the deterministic order inside the plan.
  wave integer NOT NULL CHECK (wave >= 0),
  position integer NOT NULL CHECK (position >= 0),
  state varchar(16) NOT NULL CHECK (state IN
    ('PENDING','RUNNING','SUCCEEDED','FAILED','UNKNOWN','SKIPPED','ROLLING_BACK','ROLLED_BACK')),
  skip_reason varchar(40),
  planned_actions text[] NOT NULL DEFAULT '{}',
  -- Facts captured immediately before this member was first mutated; used only for compensation.
  baseline jsonb,
  failure_code varchar(128),
  safe_message varchar(300),
  rollback_failure_code varchar(128),
  started_at timestamptz,
  finished_at timestamptz,
  -- True while the owning rollout is active. Maintained by trigger; backs the one-active-per-node rule.
  active boolean NOT NULL DEFAULT false,
  version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
  updated_at timestamptz NOT NULL,
  UNIQUE (id, organization_id),
  UNIQUE (id, rollout_id, organization_id),
  CONSTRAINT uq_fleet_rollout_member_membership UNIQUE (rollout_id, membership_id),
  CONSTRAINT fk_fleet_rollout_member_rollout FOREIGN KEY (rollout_id, fleet_id, organization_id)
    REFERENCES remnawave_fleet_rollout(id, fleet_id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_fleet_rollout_member_membership FOREIGN KEY (membership_id, fleet_id, organization_id)
    REFERENCES remnawave_fleet_membership(id, fleet_id, organization_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_fleet_rollout_member_active_node
  ON remnawave_fleet_rollout_member (organization_id, inventory_node_id) WHERE active;
CREATE UNIQUE INDEX uq_fleet_rollout_member_active_resource
  ON remnawave_fleet_rollout_member (organization_id, resource_id) WHERE active;
CREATE INDEX ix_fleet_rollout_member_rollout ON remnawave_fleet_rollout_member (rollout_id, wave, position);

-- The journal of exact child operations. A child is always named by its own id.
CREATE TABLE remnawave_fleet_rollout_action (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  rollout_id uuid NOT NULL,
  member_id uuid,
  direction varchar(8) NOT NULL CHECK (direction IN ('FORWARD','ROLLBACK')),
  kind varchar(32) NOT NULL CHECK (kind IN ('SERVER_PROFILE_ASSIGN','SERVER_PROFILE_APPLY','NETWORK_FIREWALL',
    'NODE_PORT','DESIRED_STATE','VERIFY','CONFIG_ROLLOUT')),
  sequence integer NOT NULL CHECK (sequence >= 0),
  state varchar(16) NOT NULL CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','UNKNOWN','SKIPPED')),
  -- Deterministic idempotency key handed to the child service, fixed before the child is started.
  child_request_id uuid NOT NULL,
  server_profile_plan_id uuid,
  server_profile_run_id uuid,
  config_rollout_id uuid,
  config_deployment_id uuid,
  desired_state_action_id uuid,
  intent jsonb,
  failure_code varchar(128),
  safe_message varchar(300),
  started_at timestamptz,
  finished_at timestamptz,
  version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
  updated_at timestamptz NOT NULL,
  UNIQUE (id, organization_id),
  CONSTRAINT fk_fleet_rollout_action_rollout FOREIGN KEY (rollout_id, organization_id)
    REFERENCES remnawave_fleet_rollout(id, organization_id) ON DELETE CASCADE,
  CONSTRAINT fk_fleet_rollout_action_member FOREIGN KEY (member_id, rollout_id, organization_id)
    REFERENCES remnawave_fleet_rollout_member(id, rollout_id, organization_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_fleet_rollout_action_member_order
  ON remnawave_fleet_rollout_action (rollout_id, member_id, direction, sequence) WHERE member_id IS NOT NULL;
CREATE UNIQUE INDEX uq_fleet_rollout_action_shared_order
  ON remnawave_fleet_rollout_action (rollout_id, direction, sequence) WHERE member_id IS NULL;

-- The plan is a historical fact.
CREATE FUNCTION infradesk_fleet_rollout_snapshot_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.input_snapshot IS DISTINCT FROM OLD.input_snapshot OR NEW.snapshot_hash IS DISTINCT FROM OLD.snapshot_hash
     OR NEW.fleet_revision_id IS DISTINCT FROM OLD.fleet_revision_id OR NEW.fleet_id IS DISTINCT FROM OLD.fleet_id
     OR NEW.organization_id<>OLD.organization_id OR NEW.integration_id<>OLD.integration_id
     OR NEW.created_by<>OLD.created_by OR NEW.created_at<>OLD.created_at THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_SNAPSHOT_IMMUTABLE';
  END IF;
  IF OLD.state IN ('SUCCEEDED','FAILED','UNKNOWN','ROLLED_BACK') AND NEW.state<>OLD.state THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_TERMINAL' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_fleet_rollout_snapshot_immutable BEFORE UPDATE ON remnawave_fleet_rollout
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_snapshot_immutable();

-- Members hold the per-node lock exactly while the rollout is active.
CREATE FUNCTION infradesk_fleet_rollout_member_lock() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE is_active boolean;
BEGIN
  is_active := NEW.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK');
  IF is_active IS DISTINCT FROM (OLD.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) THEN
    UPDATE remnawave_fleet_rollout_member SET active = is_active WHERE rollout_id = NEW.id;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_fleet_rollout_member_lock AFTER UPDATE OF state ON remnawave_fleet_rollout
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_member_lock();

-- Backstop: a fleet that is being rolled out cannot be re-pointed, archived or re-membered.
CREATE FUNCTION infradesk_fleet_rollout_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;
BEGIN
  IF TG_TABLE_NAME = 'remnawave_fleet' THEN
    target := NEW.id;
    IF NEW.desired_revision_id IS NOT DISTINCT FROM OLD.desired_revision_id AND NEW.archived = OLD.archived THEN
      RETURN NEW;
    END IF;
  ELSE
    target := NEW.fleet_id;
    IF TG_OP = 'UPDATE' AND NEW.removed_at IS NOT DISTINCT FROM OLD.removed_at THEN RETURN NEW; END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM remnawave_fleet_rollout r WHERE r.fleet_id = target
             AND r.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_ACTIVE' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_fleet_rollout_guard BEFORE UPDATE ON remnawave_fleet
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_guard();
CREATE TRIGGER remnawave_fleet_membership_rollout_guard
  BEFORE INSERT OR UPDATE OF removed_at ON remnawave_fleet_membership
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_guard();

-- Parent correlation is backend-only and transaction-local. Child workflows retain it durably,
-- so their own workers can advance after the orchestrator releases its current claim.
ALTER TABLE provisioning_run ADD COLUMN fleet_rollout_parent_id uuid REFERENCES remnawave_fleet_rollout(id);
ALTER TABLE integration_config_rollout ADD COLUMN fleet_rollout_parent_id uuid REFERENCES remnawave_fleet_rollout(id);
ALTER TABLE integration_config_deployment ADD COLUMN fleet_rollout_parent_id uuid REFERENCES remnawave_fleet_rollout(id);
ALTER TABLE integration_desired_state ADD COLUMN fleet_rollout_parent_id uuid REFERENCES remnawave_fleet_rollout(id);
ALTER TABLE integration_action_execution ADD COLUMN fleet_rollout_parent_id uuid REFERENCES remnawave_fleet_rollout(id);

CREATE FUNCTION infradesk_fleet_rollout_caller() RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE parent uuid; token uuid;
BEGIN
  parent := nullif(current_setting('infradesk.fleet_rollout_id',true),'')::uuid;
  IF parent IS NULL THEN RETURN NULL; END IF;
  token := nullif(current_setting('infradesk.fleet_rollout_token',true),'')::uuid;
  IF NOT EXISTS (SELECT 1 FROM remnawave_fleet_rollout WHERE id=parent AND claim_token=token
    AND claim_deadline>clock_timestamp() AND state IN ('QUEUED','RUNNING','ROLLING_BACK')) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_LEASE_LOST' USING ERRCODE='23514';
  END IF;
  RETURN parent;
END $$;

-- Admission and all competing engines serialize on the same resource key used by 25A/B/C.
CREATE FUNCTION infradesk_fleet_rollout_admission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE member_row record; object_id uuid; source_id uuid; source_at timestamptz;
BEGIN
  IF NEW.state NOT IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') OR
    OLD.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') THEN RETURN NEW; END IF;
  object_id := (NEW.input_snapshot->'shared'->>'inventoryConfigProfileId')::uuid;
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text || ':config:' || object_id::text,1));
  FOR member_row IN SELECT * FROM remnawave_fleet_rollout_member WHERE rollout_id=NEW.id ORDER BY resource_id LOOP
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text || ':' || member_row.resource_id::text,1));
    SELECT (m->'baseline'->>'sourceConnectionId')::uuid,(m->'baseline'->>'sourceUpdatedAt')::timestamptz
      INTO source_id,source_at FROM jsonb_array_elements(NEW.input_snapshot->'members') m
      WHERE m->>'resourceId'=member_row.resource_id::text;
    IF source_id IS NOT NULL THEN
      PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text || ':source:' || source_id::text,1));
      IF NOT EXISTS(SELECT 1 FROM connection c JOIN external_ref e ON e.connection_id=c.id
        WHERE c.id=source_id AND c.organization_id=NEW.organization_id AND c.updated_at=source_at
          AND c.is_active AND c.connector_type='SSH' AND e.resource_id=member_row.resource_id) OR
        (SELECT count(DISTINCT c.id) FROM connection c JOIN external_ref e ON e.connection_id=c.id
          WHERE e.organization_id=NEW.organization_id AND e.resource_id=member_row.resource_id
            AND c.is_active AND c.connector_type='SSH')<>1 THEN
        RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_PLAN_CHANGED' USING ERRCODE='23514';
      END IF;
    END IF;
    IF EXISTS(SELECT 1 FROM provisioning_run WHERE organization_id=NEW.organization_id
      AND resource_id=member_row.resource_id AND status IN ('QUEUED','RUNNING')) OR
      EXISTS(SELECT 1 FROM remnawave_node_onboarding WHERE organization_id=NEW.organization_id
      AND resource_id=member_row.resource_id AND state IN ('QUEUED','RUNNING')) OR
      EXISTS(SELECT 1 FROM configuration_deployment WHERE organization_id=NEW.organization_id
      AND resource_id=member_row.resource_id AND state IN ('QUEUED','RUNNING')) OR
      EXISTS(SELECT 1 FROM operation_execution WHERE organization_id=NEW.organization_id
      AND resource_id=member_row.resource_id AND status='RUNNING') OR
      EXISTS(SELECT 1 FROM integration_action_execution WHERE organization_id=NEW.organization_id
      AND inventory_object_id=member_row.inventory_node_id AND status IN ('QUEUED','RUNNING')) THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_RESOURCE_BUSY' USING ERRCODE='23514';
    END IF;
  END LOOP;
  IF EXISTS(SELECT 1 FROM integration_config_deployment WHERE organization_id=NEW.organization_id
    AND inventory_object_id=object_id AND status IN ('QUEUED','RUNNING')) OR
    EXISTS(SELECT 1 FROM integration_config_rollout WHERE organization_id=NEW.organization_id
    AND inventory_object_id=object_id AND status IN ('PREPARING','APPLYING','VERIFYING','ROLLBACK_APPLYING','ROLLBACK_VERIFYING')) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_RESOURCE_BUSY' USING ERRCODE='23514';
  END IF;
  IF EXISTS(SELECT 1 FROM remnawave_fleet_rollout r WHERE r.organization_id=NEW.organization_id
    AND r.id<>NEW.id AND r.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')
    AND r.input_snapshot->'shared'->>'inventoryConfigProfileId'=object_id::text
    AND ((NEW.input_snapshot->'shared'->>'required')::boolean OR (r.input_snapshot->'shared'->>'required')::boolean)) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_SHARED_CONFIG_BUSY' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_fleet_rollout_admission BEFORE UPDATE OF state ON remnawave_fleet_rollout
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_admission();

CREATE FUNCTION infradesk_fleet_rollout_resource_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent uuid; active_parent uuid; row_state text;
BEGIN
  IF TG_TABLE_NAME IN ('configuration_deployment','remnawave_node_onboarding') THEN row_state:=NEW.state;
  ELSE row_state:=NEW.status; END IF;
  IF row_state NOT IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
  IF TG_OP='UPDATE' THEN
    IF TG_TABLE_NAME IN ('configuration_deployment','remnawave_node_onboarding') THEN
      IF OLD.state IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
    ELSE IF OLD.status IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF; END IF;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text || ':' || NEW.resource_id::text,1));
  parent:=infradesk_fleet_rollout_caller();
  SELECT rollout_id INTO active_parent FROM remnawave_fleet_rollout_member WHERE organization_id=NEW.organization_id
    AND resource_id=NEW.resource_id AND active;
  IF active_parent IS NOT NULL THEN
    IF TG_TABLE_NAME<>'provisioning_run' OR parent IS DISTINCT FROM active_parent THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_RESOURCE_BUSY' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM remnawave_fleet_rollout_action a JOIN remnawave_fleet_rollout_member m ON m.id=a.member_id
      WHERE a.rollout_id=parent AND a.organization_id=NEW.organization_id AND m.resource_id=NEW.resource_id
      AND a.kind='SERVER_PROFILE_APPLY' AND a.state='RUNNING' AND a.server_profile_plan_id=NEW.id
      AND a.child_request_id=NEW.request_id) THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_CHILD_MISMATCH' USING ERRCODE='23514';
    END IF;
    NEW.fleet_rollout_parent_id:=parent;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER provisioning_fleet_rollout_guard BEFORE INSERT OR UPDATE OF status ON provisioning_run
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_resource_guard();
CREATE TRIGGER configuration_fleet_rollout_guard BEFORE INSERT OR UPDATE OF state ON configuration_deployment
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_resource_guard();
CREATE TRIGGER operation_fleet_rollout_guard BEFORE INSERT OR UPDATE OF status ON operation_execution
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_resource_guard();
CREATE TRIGGER onboarding_fleet_rollout_guard BEFORE INSERT OR UPDATE OF state ON remnawave_node_onboarding
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_resource_guard();

CREATE FUNCTION infradesk_fleet_rollout_metadata_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE org uuid; resource uuid; parent uuid; owner uuid;
BEGIN
  IF TG_OP='DELETE' THEN org:=OLD.organization_id; ELSE org:=NEW.organization_id; END IF;
  IF TG_TABLE_NAME IN ('server_profile_assignment','integration_resource_binding','external_ref') THEN
    IF TG_OP='DELETE' THEN resource:=OLD.resource_id; ELSE resource:=NEW.resource_id; END IF;
  ELSE
    IF TG_OP='DELETE' THEN
      SELECT resource_id INTO resource FROM integration_resource_binding WHERE inventory_object_id=OLD.inventory_object_id;
    ELSE
      SELECT resource_id INTO resource FROM integration_resource_binding WHERE inventory_object_id=NEW.inventory_object_id;
    END IF;
  END IF;
  IF resource IS NOT NULL THEN
    PERFORM pg_advisory_xact_lock(hashtextextended(org::text || ':' || resource::text,1));
    SELECT rollout_id INTO owner FROM remnawave_fleet_rollout_member WHERE organization_id=org AND resource_id=resource AND active;
    parent:=infradesk_fleet_rollout_caller();
    IF owner IS NOT NULL AND (TG_TABLE_NAME IN ('integration_resource_binding','external_ref') OR parent IS DISTINCT FROM owner) THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_RESOURCE_BUSY' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='integration_desired_state' AND TG_OP<>'DELETE' THEN NEW.fleet_rollout_parent_id:=parent; END IF;
  END IF;
  IF TG_TABLE_NAME IN ('integration_resource_binding','external_ref') THEN
    IF TG_OP='UPDATE' AND OLD.resource_id IS DISTINCT FROM NEW.resource_id THEN
      PERFORM pg_advisory_xact_lock(hashtextextended(OLD.organization_id::text || ':' || OLD.resource_id::text,1));
      IF EXISTS(SELECT 1 FROM remnawave_fleet_rollout_member WHERE organization_id=OLD.organization_id
        AND resource_id=OLD.resource_id AND active) THEN
        RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_RESOURCE_BUSY' USING ERRCODE='23514';
      END IF;
    END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER assignment_fleet_rollout_guard BEFORE INSERT OR UPDATE OR DELETE ON server_profile_assignment
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_metadata_guard();
CREATE TRIGGER binding_fleet_rollout_guard BEFORE INSERT OR UPDATE OR DELETE ON integration_resource_binding
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_metadata_guard();
CREATE TRIGGER desired_fleet_rollout_guard BEFORE INSERT OR UPDATE OF desired_state OR DELETE ON integration_desired_state
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_metadata_guard();
CREATE TRIGGER source_ref_fleet_rollout_guard BEFORE INSERT OR UPDATE OR DELETE ON external_ref
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_metadata_guard();

CREATE FUNCTION infradesk_fleet_rollout_integration_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent uuid; owner uuid; obj uuid; resource uuid;
BEGIN
  IF TG_TABLE_NAME='integration_action_execution' THEN
    IF NEW.status NOT IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND OLD.status IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
    obj:=NEW.inventory_object_id;
    SELECT resource_id INTO resource FROM integration_resource_binding WHERE inventory_object_id=obj;
    IF resource IS NOT NULL THEN
      PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text || ':' || resource::text,1));
    END IF;
    SELECT rollout_id INTO owner FROM remnawave_fleet_rollout_member WHERE organization_id=NEW.organization_id
      AND inventory_node_id=obj AND active;
    IF NEW.source='DESIRED_STATE' THEN
      SELECT fleet_rollout_parent_id INTO parent FROM integration_desired_state WHERE id=NEW.desired_state_id_snapshot
        AND version=NEW.desired_state_version_snapshot;
    END IF;
    IF owner IS NOT NULL AND parent=owner AND NOT EXISTS(SELECT 1 FROM remnawave_fleet_rollout_action a
      JOIN remnawave_fleet_rollout_member m ON m.id=a.member_id
      WHERE a.rollout_id=parent AND m.inventory_node_id=obj AND a.kind='DESIRED_STATE'
        AND a.state IN ('RUNNING','SUCCEEDED')) THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_CHILD_MISMATCH' USING ERRCODE='23514';
    END IF;
  ELSE
    IF TG_TABLE_NAME='integration_config_rollout' THEN
      IF NEW.status<>'PREPARING' OR TG_OP<>'INSERT' THEN RETURN NEW; END IF;
    ELSE
      IF NEW.status NOT IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
      IF TG_OP='UPDATE' AND OLD.status IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
    END IF;
    obj:=NEW.inventory_object_id;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text || ':config:' || obj::text,1));
    SELECT r.id INTO owner FROM remnawave_fleet_rollout r WHERE r.organization_id=NEW.organization_id
      AND r.integration_id=NEW.integration_id AND r.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')
      AND r.input_snapshot->'shared'->>'inventoryConfigProfileId'=obj::text LIMIT 1;
    IF TG_TABLE_NAME='integration_config_rollout' THEN
      parent:=infradesk_fleet_rollout_caller();
      IF owner IS NOT NULL AND parent=owner AND NOT EXISTS(SELECT 1 FROM remnawave_fleet_rollout_action
        WHERE rollout_id=parent AND kind='CONFIG_ROLLOUT' AND state='RUNNING' AND child_request_id=NEW.request_id) THEN
        RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_CHILD_MISMATCH' USING ERRCODE='23514';
      END IF;
    ELSE
      SELECT fleet_rollout_parent_id INTO parent FROM integration_config_rollout WHERE id=NEW.rollout_id;
    END IF;
  END IF;
  IF owner IS NOT NULL AND parent IS DISTINCT FROM owner THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_RESOURCE_BUSY' USING ERRCODE='23514';
  END IF;
  NEW.fleet_rollout_parent_id:=parent;
  RETURN NEW;
END $$;
CREATE TRIGGER action_fleet_rollout_guard BEFORE INSERT OR UPDATE OF status ON integration_action_execution
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_integration_guard();
CREATE TRIGGER config_rollout_fleet_rollout_guard BEFORE INSERT ON integration_config_rollout
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_integration_guard();
CREATE TRIGGER config_deployment_fleet_rollout_guard BEFORE INSERT OR UPDATE OF status ON integration_config_deployment
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_integration_guard();

CREATE FUNCTION infradesk_fleet_rollout_desired_child() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.fleet_rollout_parent_id IS NOT NULL THEN
    UPDATE remnawave_fleet_rollout_action a SET desired_state_action_id=NEW.id
      FROM remnawave_fleet_rollout_member m, remnawave_fleet_rollout r
      WHERE a.member_id=m.id AND a.rollout_id=r.id AND r.id=NEW.fleet_rollout_parent_id
        AND m.inventory_node_id=NEW.inventory_object_id AND a.kind='DESIRED_STATE'
        AND a.direction=CASE WHEN r.state='ROLLING_BACK' THEN 'ROLLBACK' ELSE 'FORWARD' END;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER desired_action_fleet_rollout_child AFTER INSERT ON integration_action_execution
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_desired_child();

CREATE FUNCTION infradesk_fleet_rollout_connection_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(OLD.organization_id::text || ':source:' || OLD.id::text,1));
  IF EXISTS(SELECT 1 FROM remnawave_fleet_rollout r,
    jsonb_array_elements(r.input_snapshot->'members') m
    WHERE r.organization_id=OLD.organization_id AND r.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')
      AND m->'baseline'->>'sourceConnectionId'=OLD.id::text) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_ROLLOUT_SOURCE_BUSY' USING ERRCODE='23514';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER connection_fleet_rollout_guard BEFORE UPDATE OR DELETE ON connection
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_rollout_connection_guard();

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
  'REMNAWAVE_FLEET_MEMBER_ADDED','REMNAWAVE_FLEET_MEMBER_REMOVED',
  'REMNAWAVE_FLEET_ROLLOUT_REQUESTED','REMNAWAVE_FLEET_ROLLOUT_PAUSED','REMNAWAVE_FLEET_ROLLOUT_RESUMED',
  'REMNAWAVE_FLEET_ROLLOUT_ROLLBACK_REQUESTED','REMNAWAVE_FLEET_ROLLOUT_COMPLETED','REMNAWAVE_FLEET_ROLLOUT_FAILED'
));
