-- Stage25F: reviewed software releases, separate from configuration FleetRevision (V49).
CREATE TABLE remnawave_fleet_release_revision (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  fleet_id uuid NOT NULL,
  revision_number integer NOT NULL CHECK (revision_number > 0),
  release_id varchar(40) NOT NULL CHECK (release_id ~ '^node-[0-9]+\.[0-9]+\.[0-9]+$'),
  node_version varchar(24) NOT NULL,
  image_repository varchar(80) NOT NULL CHECK (image_repository = 'ghcr.io/remnawave/node'),
  image_digest varchar(71) NOT NULL CHECK (image_digest ~ '^sha256:[0-9a-f]{64}$'),
  catalog_version integer NOT NULL CHECK (catalog_version > 0),
  release_snapshot jsonb NOT NULL CHECK (jsonb_typeof(release_snapshot) = 'object'),
  panel_evidence jsonb NOT NULL CHECK (jsonb_typeof(panel_evidence) = 'object'),
  content_hash char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  created_by uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  UNIQUE (id, fleet_id, organization_id),
  UNIQUE (fleet_id, revision_number),
  FOREIGN KEY (fleet_id, organization_id, integration_id)
    REFERENCES remnawave_fleet(id, organization_id, integration_id)
);
CREATE TABLE remnawave_fleet_release_policy (
  fleet_id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  desired_revision_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  updated_at timestamptz NOT NULL,
  FOREIGN KEY (fleet_id, organization_id) REFERENCES remnawave_fleet(id, organization_id),
  FOREIGN KEY (desired_revision_id, fleet_id, organization_id)
    REFERENCES remnawave_fleet_release_revision(id, fleet_id, organization_id)
);
CREATE FUNCTION infradesk_node_release_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'REMNAWAVE_NODE_RELEASE_REVISION_IMMUTABLE' USING ERRCODE='23514';
END $$;
CREATE TRIGGER remnawave_node_release_immutable BEFORE UPDATE OR DELETE ON remnawave_fleet_release_revision
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_release_immutable();

-- Read-only observations collected by the existing Fleet observer, under its source/membership fence.
CREATE TABLE remnawave_node_image_observation (
  membership_id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  fleet_id uuid NOT NULL,
  membership_version bigint NOT NULL CHECK (membership_version > 0),
  inventory_node_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  onboarding_id uuid NOT NULL,
  source_connection_id uuid NOT NULL,
  source_updated_at timestamptz NOT NULL,
  observation jsonb NOT NULL CHECK (jsonb_typeof(observation) = 'object'),
  observed_at timestamptz NOT NULL,
  FOREIGN KEY (membership_id, fleet_id, organization_id)
    REFERENCES remnawave_fleet_membership(id, fleet_id, organization_id),
  FOREIGN KEY (onboarding_id, organization_id) REFERENCES remnawave_node_onboarding(id, organization_id),
  FOREIGN KEY (inventory_node_id, integration_id, organization_id) REFERENCES integration_inventory_object(id, integration_id, organization_id),
  FOREIGN KEY (resource_id, organization_id) REFERENCES resource(id, organization_id),
  FOREIGN KEY (source_connection_id, organization_id) REFERENCES connection(id, organization_id)
);

CREATE TABLE remnawave_fleet_upgrade_run (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  fleet_id uuid NOT NULL,
  release_revision_id uuid NOT NULL,
  request_id uuid,
  state varchar(16) NOT NULL CHECK (state IN
    ('PLANNED','QUEUED','RUNNING','PAUSED','SUCCEEDED','FAILED','UNKNOWN','ROLLING_BACK','ROLLED_BACK')),
  phase varchar(24) NOT NULL CHECK (phase IN ('VALIDATE','PREPARE_CANARY','PREFETCH_CANARY','UPGRADE_CANARY',
    'VERIFY_CANARY','PREFETCH_WAVE','UPGRADE_WAVE','VERIFY_WAVE','FINAL_VERIFY','ROLLBACK','COMPLETE')),
  input_snapshot jsonb NOT NULL CHECK (jsonb_typeof(input_snapshot)='object'),
  snapshot_hash char(64) NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
  current_wave integer NOT NULL CHECK (current_wave >= 0),
  wave_count integer NOT NULL CHECK (wave_count >= 0 AND current_wave <= wave_count),
  created_by uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL CHECK (expires_at > created_at AND expires_at <= created_at + interval '24 hours'),
  started_at timestamptz,
  finished_at timestamptz,
  failure_code varchar(128),
  pause_reason varchar(40),
  paused_at timestamptz,
  pause_requested_at timestamptz,
  rollback_requested_at timestamptz,
  rollback_scope varchar(16) NOT NULL CHECK (rollback_scope IN ('CURRENT_WAVE','ALL_COMPLETED')),
  rollback_incomplete boolean NOT NULL DEFAULT false,
  next_run_at timestamptz NOT NULL,
  phase_started_at timestamptz NOT NULL,
  claim_owner uuid,
  claim_token uuid,
  claim_deadline timestamptz,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  updated_at timestamptz NOT NULL,
  UNIQUE (id, organization_id),
  UNIQUE (id, fleet_id, organization_id),
  FOREIGN KEY (fleet_id, organization_id, integration_id) REFERENCES remnawave_fleet(id, organization_id, integration_id),
  FOREIGN KEY (release_revision_id, fleet_id, organization_id)
    REFERENCES remnawave_fleet_release_revision(id, fleet_id, organization_id),
  CHECK ((claim_owner IS NULL) = (claim_token IS NULL) AND (claim_token IS NULL) = (claim_deadline IS NULL)),
  CHECK (state='PLANNED' OR request_id IS NOT NULL OR finished_at IS NOT NULL)
);
CREATE UNIQUE INDEX uq_fleet_upgrade_request ON remnawave_fleet_upgrade_run(organization_id,request_id)
  WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_fleet_upgrade_active ON remnawave_fleet_upgrade_run(fleet_id)
  WHERE state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK');
CREATE INDEX ix_fleet_upgrade_due ON remnawave_fleet_upgrade_run(next_run_at,claim_deadline)
  WHERE state IN ('QUEUED','RUNNING','ROLLING_BACK');
CREATE INDEX ix_fleet_upgrade_history ON remnawave_fleet_upgrade_run(organization_id,fleet_id,created_at DESC);
CREATE TABLE remnawave_fleet_upgrade_member (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  upgrade_id uuid NOT NULL,
  fleet_id uuid NOT NULL,
  membership_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  wave integer NOT NULL CHECK (wave >= 0),
  position integer NOT NULL CHECK (position >= 0),
  state varchar(16) NOT NULL CHECK (state IN
    ('PENDING','RUNNING','SUCCEEDED','FAILED','UNKNOWN','SKIPPED','ROLLING_BACK','ROLLED_BACK')),
  failure_code varchar(128),
  local_verified_at timestamptz,
  panel_verified_at timestamptz,
  last_observation jsonb,
  started_at timestamptz,
  finished_at timestamptz,
  active boolean NOT NULL DEFAULT false,
  UNIQUE (id, upgrade_id, organization_id),
  UNIQUE (upgrade_id,membership_id),
  UNIQUE (upgrade_id,resource_id),
  UNIQUE (upgrade_id,position),
  FOREIGN KEY (upgrade_id,fleet_id,organization_id) REFERENCES remnawave_fleet_upgrade_run(id,fleet_id,organization_id),
  FOREIGN KEY (membership_id,fleet_id,organization_id) REFERENCES remnawave_fleet_membership(id,fleet_id,organization_id),
  FOREIGN KEY (resource_id,organization_id) REFERENCES resource(id,organization_id)
);
CREATE UNIQUE INDEX uq_fleet_upgrade_active_resource ON remnawave_fleet_upgrade_member(organization_id,resource_id)
  WHERE active;
CREATE TABLE remnawave_fleet_upgrade_action (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  upgrade_id uuid NOT NULL,
  member_id uuid NOT NULL,
  kind varchar(24) NOT NULL CHECK (kind IN ('PREFETCH','SWITCH','LOCAL_VERIFY','PANEL_VERIFY')),
  rollback boolean NOT NULL,
  state varchar(16) NOT NULL CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','UNKNOWN','SKIPPED')),
  target_reference varchar(180) NOT NULL CHECK (target_reference ~ '^ghcr\.io/remnawave/node@sha256:[0-9a-f]{64}$'),
  failure_code varchar(128),
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  UNIQUE (upgrade_id,member_id,kind,rollback),
  FOREIGN KEY (upgrade_id,organization_id) REFERENCES remnawave_fleet_upgrade_run(id,organization_id),
  FOREIGN KEY (member_id,upgrade_id,organization_id) REFERENCES remnawave_fleet_upgrade_member(id,upgrade_id,organization_id)
);

CREATE FUNCTION infradesk_fleet_upgrade_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.state IN ('SUCCEEDED','FAILED','UNKNOWN','ROLLED_BACK') THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_TERMINAL' USING ERRCODE='23514';
  END IF;
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_HISTORY_IMMUTABLE' USING ERRCODE='23514';
  END IF;
  IF (NEW.id,NEW.organization_id,NEW.integration_id,NEW.fleet_id,NEW.release_revision_id,
      NEW.input_snapshot,NEW.snapshot_hash,NEW.wave_count,NEW.created_by,NEW.created_at,NEW.expires_at)
    IS DISTINCT FROM (OLD.id,OLD.organization_id,OLD.integration_id,OLD.fleet_id,OLD.release_revision_id,
      OLD.input_snapshot,OLD.snapshot_hash,OLD.wave_count,OLD.created_by,OLD.created_at,OLD.expires_at) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_SNAPSHOT_IMMUTABLE' USING ERRCODE='23514';
  END IF;
  IF OLD.request_id IS NOT NULL AND NEW.request_id IS DISTINCT FROM OLD.request_id THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_REQUEST_IMMUTABLE' USING ERRCODE='23514';
  END IF;
  IF NEW.state='SUCCEEDED' AND (NEW.phase<>'COMPLETE' OR EXISTS(
    SELECT 1 FROM remnawave_fleet_upgrade_member WHERE upgrade_id=NEW.id AND state NOT IN ('SUCCEEDED','SKIPPED'))) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_VERIFICATION_REQUIRED' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_fleet_upgrade_snapshot_guard BEFORE UPDATE OR DELETE ON remnawave_fleet_upgrade_run
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_upgrade_snapshot_guard();
CREATE FUNCTION infradesk_fleet_upgrade_member_locks() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) IS DISTINCT FROM
     (OLD.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) THEN
    UPDATE remnawave_fleet_upgrade_member SET active=NEW.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')
      WHERE upgrade_id=NEW.id;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_fleet_upgrade_member_locks AFTER UPDATE OF state ON remnawave_fleet_upgrade_run
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_upgrade_member_locks();
CREATE FUNCTION infradesk_fleet_upgrade_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent remnawave_fleet_upgrade_run; item jsonb;
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_HISTORY_IMMUTABLE' USING ERRCODE='23514';
  END IF;
  SELECT * INTO parent FROM remnawave_fleet_upgrade_run WHERE id=NEW.upgrade_id AND organization_id=NEW.organization_id;
  -- Only the parent's own lock-maintenance trigger may change active without changing execution facts.
  IF TG_TABLE_NAME='remnawave_fleet_upgrade_member' THEN
    IF TG_OP='UPDATE' AND pg_trigger_depth()>1 AND NEW.active=(parent.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) AND
      (to_jsonb(NEW)-'active')=(to_jsonb(OLD)-'active') THEN RETURN NEW; END IF;
  END IF;
  IF parent.state IN ('SUCCEEDED','FAILED','UNKNOWN','ROLLED_BACK') THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_TERMINAL' USING ERRCODE='23514';
  END IF;
  IF TG_TABLE_NAME='remnawave_fleet_upgrade_member' THEN
    IF NEW.active IS DISTINCT FROM (parent.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_LOCK_IMMUTABLE' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND OLD.state='UNKNOWN' AND NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_UNKNOWN' USING ERRCODE='23514';
    END IF;
    IF NEW.state='SUCCEEDED' AND (NEW.local_verified_at IS NULL OR NEW.panel_verified_at IS NULL) THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_UNVERIFIED' USING ERRCODE='23514';
    END IF;
    SELECT m INTO item FROM jsonb_array_elements(parent.input_snapshot->'members') m
      WHERE m->>'membershipId'=NEW.membership_id::text;
    IF item IS NULL OR item->>'resourceId'<>NEW.resource_id::text OR (item->>'wave')::integer<>NEW.wave OR
      (item->>'position')::integer<>NEW.position OR NEW.wave>=greatest(parent.wave_count,1) THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_SNAPSHOT_IMMUTABLE' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (NEW.id,NEW.organization_id,NEW.upgrade_id,NEW.fleet_id,NEW.membership_id,NEW.resource_id,NEW.wave,NEW.position)
      IS DISTINCT FROM (OLD.id,OLD.organization_id,OLD.upgrade_id,OLD.fleet_id,OLD.membership_id,OLD.resource_id,OLD.wave,OLD.position) THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_SNAPSHOT_IMMUTABLE' USING ERRCODE='23514';
    END IF;
  ELSIF TG_OP='UPDATE' AND (NEW.id,NEW.organization_id,NEW.upgrade_id,NEW.member_id,NEW.kind,NEW.rollback,NEW.target_reference)
    IS DISTINCT FROM (OLD.id,OLD.organization_id,OLD.upgrade_id,OLD.member_id,OLD.kind,OLD.rollback,OLD.target_reference) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_ACTION_IMMUTABLE' USING ERRCODE='23514';
  END IF;
  IF TG_TABLE_NAME='remnawave_fleet_upgrade_action' AND TG_OP='UPDATE' AND
    OLD.state IN ('SUCCEEDED','FAILED','UNKNOWN','SKIPPED') AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_ACTION_IMMUTABLE' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_fleet_upgrade_member_guard BEFORE INSERT OR UPDATE OR DELETE ON remnawave_fleet_upgrade_member
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_upgrade_child_guard();
CREATE TRIGGER remnawave_fleet_upgrade_action_guard BEFORE INSERT OR UPDATE OR DELETE ON remnawave_fleet_upgrade_action
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_upgrade_child_guard();

-- All engines use the same advisory resource/config/source keys as V50. Both start directions
-- take the locks before checking conflicts, so simultaneous approvals cannot both win.
CREATE FUNCTION infradesk_assert_no_node_upgrade(org uuid, resource uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(org::text||':'||resource::text,1));
  IF EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_member WHERE organization_id=org AND resource_id=resource AND active) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_RESOURCE_BUSY' USING ERRCODE='23514';
  END IF;
END $$;
CREATE FUNCTION infradesk_fleet_upgrade_admission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m record; pin jsonb; config uuid;
BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.state<>'PLANNED' THEN RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_PLAN_REQUIRED' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  IF NEW.state NOT IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') OR
    OLD.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') THEN RETURN NEW; END IF;
  config := (NEW.input_snapshot->'configuration'->>'inventoryConfigProfileId')::uuid;
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':fleet:'||NEW.fleet_id::text,2));
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':config:'||config::text,1));
  IF EXISTS(SELECT 1 FROM integration_config_deployment WHERE organization_id=NEW.organization_id
    AND inventory_object_id=config AND status IN ('QUEUED','RUNNING')) OR
    EXISTS(SELECT 1 FROM integration_config_rollout WHERE organization_id=NEW.organization_id
      AND inventory_object_id=config AND status IN ('PREPARING','APPLYING','VERIFYING','ROLLBACK_APPLYING','ROLLBACK_VERIFYING')) OR
    EXISTS(SELECT 1 FROM remnawave_fleet_rollout WHERE organization_id=NEW.organization_id
      AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') AND
      (fleet_id=NEW.fleet_id OR input_snapshot->'shared'->>'inventoryConfigProfileId'=config::text)) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_RESOURCE_BUSY' USING ERRCODE='23514';
  END IF;
  FOR m IN SELECT * FROM remnawave_fleet_upgrade_member WHERE upgrade_id=NEW.id ORDER BY resource_id LOOP
    PERFORM infradesk_assert_no_node_upgrade(NEW.organization_id,m.resource_id);
    SELECT value INTO pin FROM jsonb_array_elements(NEW.input_snapshot->'members') WHERE value->>'membershipId'=m.membership_id::text;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':source:'||(pin->>'sourceConnectionId'),1));
    IF NOT EXISTS(SELECT 1 FROM connection c JOIN external_ref e ON e.connection_id=c.id AND e.organization_id=c.organization_id
      WHERE c.organization_id=NEW.organization_id AND c.id=(pin->>'sourceConnectionId')::uuid AND
        c.updated_at=(pin->>'sourceUpdatedAt')::timestamptz AND c.is_active AND c.connector_type='SSH' AND e.resource_id=m.resource_id) OR
      (SELECT count(DISTINCT c.id) FROM connection c JOIN external_ref e ON e.connection_id=c.id AND e.organization_id=c.organization_id
        WHERE c.organization_id=NEW.organization_id AND e.resource_id=m.resource_id AND c.is_active AND c.connector_type='SSH')<>1 THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_PLAN_CHANGED' USING ERRCODE='23514';
    END IF;
    IF EXISTS(SELECT 1 FROM remnawave_fleet_rollout_member WHERE organization_id=NEW.organization_id AND resource_id=m.resource_id AND active) OR
      EXISTS(SELECT 1 FROM provisioning_run WHERE organization_id=NEW.organization_id AND resource_id=m.resource_id AND status IN ('QUEUED','RUNNING')) OR
      EXISTS(SELECT 1 FROM configuration_deployment WHERE organization_id=NEW.organization_id AND resource_id=m.resource_id AND state IN ('QUEUED','RUNNING')) OR
      EXISTS(SELECT 1 FROM remnawave_node_onboarding WHERE organization_id=NEW.organization_id AND resource_id=m.resource_id AND state IN ('QUEUED','RUNNING')) OR
      EXISTS(SELECT 1 FROM operation_execution WHERE organization_id=NEW.organization_id AND resource_id=m.resource_id AND status='RUNNING') OR
      EXISTS(SELECT 1 FROM integration_action_execution WHERE organization_id=NEW.organization_id
        AND inventory_object_id=(pin->>'inventoryNodeId')::uuid AND status IN ('QUEUED','RUNNING')) THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_RESOURCE_BUSY' USING ERRCODE='23514';
    END IF;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_fleet_upgrade_admission BEFORE INSERT OR UPDATE OF state ON remnawave_fleet_upgrade_run
  FOR EACH ROW EXECUTE FUNCTION infradesk_fleet_upgrade_admission();
CREATE FUNCTION infradesk_node_upgrade_engine_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE state text;
BEGIN
  IF TG_TABLE_NAME IN ('provisioning_run','operation_execution') THEN state:=NEW.status; ELSE state:=NEW.state; END IF;
  IF state IN ('QUEUED','RUNNING') THEN PERFORM infradesk_assert_no_node_upgrade(NEW.organization_id,NEW.resource_id); END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER provisioning_node_upgrade_guard BEFORE INSERT OR UPDATE OF status ON provisioning_run
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_engine_guard();
CREATE TRIGGER operation_node_upgrade_guard BEFORE INSERT OR UPDATE OF status ON operation_execution
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_engine_guard();
CREATE TRIGGER configuration_node_upgrade_guard BEFORE INSERT OR UPDATE OF state ON configuration_deployment
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_engine_guard();
CREATE TRIGGER onboarding_node_upgrade_guard BEFORE INSERT OR UPDATE OF state ON remnawave_node_onboarding
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_engine_guard();
CREATE FUNCTION infradesk_node_upgrade_rollout_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m record; config uuid;
BEGIN
  IF NEW.state NOT IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') OR OLD.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') THEN RETURN NEW; END IF;
  config := (NEW.input_snapshot->'shared'->>'inventoryConfigProfileId')::uuid;
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':config:'||config::text,1));
  IF EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_run WHERE organization_id=NEW.organization_id
    AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') AND
      (fleet_id=NEW.fleet_id OR input_snapshot->'configuration'->>'inventoryConfigProfileId'=config::text)) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_RESOURCE_BUSY' USING ERRCODE='23514';
  END IF;
  FOR m IN SELECT resource_id FROM remnawave_fleet_rollout_member WHERE rollout_id=NEW.id ORDER BY resource_id LOOP
    PERFORM infradesk_assert_no_node_upgrade(NEW.organization_id,m.resource_id);
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_rollout_node_upgrade_guard BEFORE UPDATE OF state ON remnawave_fleet_rollout
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_rollout_guard();

CREATE FUNCTION infradesk_node_upgrade_inventory_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE obj uuid; resource uuid; config uuid;
BEGIN
  IF TG_TABLE_NAME='integration_action_execution' AND NEW.status NOT IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
  IF TG_TABLE_NAME='integration_config_deployment' AND NEW.status NOT IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
  obj := NEW.inventory_object_id;
  IF TG_TABLE_NAME='integration_action_execution' THEN
    SELECT resource_id INTO resource FROM integration_resource_binding WHERE organization_id=NEW.organization_id AND inventory_object_id=obj;
    IF resource IS NOT NULL THEN PERFORM infradesk_assert_no_node_upgrade(NEW.organization_id,resource); END IF;
  ELSE
    config := obj;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':config:'||config::text,1));
    IF EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_run WHERE organization_id=NEW.organization_id
      AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') AND
      input_snapshot->'configuration'->>'inventoryConfigProfileId'=config::text) THEN
      RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_RESOURCE_BUSY' USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER action_node_upgrade_guard BEFORE INSERT OR UPDATE OF status ON integration_action_execution
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_inventory_guard();
CREATE TRIGGER config_deployment_node_upgrade_guard BEFORE INSERT OR UPDATE OF status ON integration_config_deployment
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_inventory_guard();
CREATE TRIGGER config_rollout_node_upgrade_guard BEFORE INSERT ON integration_config_rollout
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_inventory_guard();

-- Pin changes cannot be made underneath an active software upgrade.
CREATE FUNCTION infradesk_node_upgrade_fleet_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE fleet uuid; org uuid;
BEGIN
  IF TG_TABLE_NAME='remnawave_fleet' THEN
    IF NEW.desired_revision_id IS NOT DISTINCT FROM OLD.desired_revision_id AND NEW.archived=OLD.archived THEN RETURN NEW; END IF;
    fleet := NEW.id; org := NEW.organization_id;
  ELSE fleet := NEW.fleet_id; org := NEW.organization_id; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(org::text||':fleet:'||fleet::text,2));
  IF EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_run WHERE organization_id=org AND fleet_id=fleet
    AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_ACTIVE' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER fleet_node_upgrade_guard BEFORE UPDATE ON remnawave_fleet
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_fleet_guard();
CREATE TRIGGER fleet_membership_node_upgrade_guard BEFORE INSERT OR UPDATE OF removed_at ON remnawave_fleet_membership
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_fleet_guard();
CREATE TRIGGER fleet_release_policy_upgrade_guard BEFORE INSERT OR UPDATE ON remnawave_fleet_release_policy
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_fleet_guard();

CREATE FUNCTION infradesk_node_upgrade_metadata_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE org uuid; resource uuid; previous uuid; obj uuid;
BEGIN
  IF TG_OP='DELETE' THEN org:=OLD.organization_id; ELSE org:=NEW.organization_id; END IF;
  IF TG_TABLE_NAME='integration_desired_state' THEN
    IF TG_OP='DELETE' THEN obj:=OLD.inventory_object_id; ELSE obj:=NEW.inventory_object_id; END IF;
    SELECT resource_id INTO resource FROM integration_resource_binding WHERE organization_id=org AND inventory_object_id=obj;
  ELSE
    IF TG_OP='DELETE' THEN resource:=OLD.resource_id; ELSE resource:=NEW.resource_id; END IF;
    IF TG_OP='UPDATE' THEN previous:=OLD.resource_id; END IF;
  END IF;
  IF resource IS NOT NULL THEN PERFORM infradesk_assert_no_node_upgrade(org,resource); END IF;
  IF previous IS NOT NULL AND previous IS DISTINCT FROM resource THEN PERFORM infradesk_assert_no_node_upgrade(org,previous); END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER assignment_node_upgrade_guard BEFORE INSERT OR UPDATE OR DELETE ON server_profile_assignment
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_metadata_guard();
CREATE TRIGGER binding_node_upgrade_guard BEFORE INSERT OR UPDATE OR DELETE ON integration_resource_binding
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_metadata_guard();
CREATE TRIGGER desired_node_upgrade_guard BEFORE INSERT OR UPDATE OF desired_state OR DELETE ON integration_desired_state
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_metadata_guard();
CREATE TRIGGER source_ref_node_upgrade_guard BEFORE INSERT OR UPDATE OR DELETE ON external_ref
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_metadata_guard();
CREATE FUNCTION infradesk_node_upgrade_connection_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(OLD.organization_id::text||':source:'||OLD.id::text,1));
  IF EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_run r, jsonb_array_elements(r.input_snapshot->'members') m
    WHERE r.organization_id=OLD.organization_id AND r.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')
      AND m->>'sourceConnectionId'=OLD.id::text) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_UPGRADE_SOURCE_BUSY' USING ERRCODE='23514';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER connection_node_upgrade_guard BEFORE UPDATE OR DELETE ON connection
  FOR EACH ROW EXECUTE FUNCTION infradesk_node_upgrade_connection_guard();

DO $$
DECLARE prior text;
BEGIN
  SELECT pg_get_constraintdef(oid) INTO prior FROM pg_constraint
    WHERE conrelid='audit_event'::regclass AND conname='ck_audit_event_action';
  ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
  EXECUTE 'ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (' ||
    '''REMNAWAVE_NODE_RELEASE_TARGET_CHANGED'',''REMNAWAVE_FLEET_UPGRADE_REQUESTED'',''REMNAWAVE_FLEET_UPGRADE_PAUSED'',' ||
    '''REMNAWAVE_FLEET_UPGRADE_RESUMED'',''REMNAWAVE_FLEET_UPGRADE_ROLLBACK_REQUESTED'',''REMNAWAVE_FLEET_UPGRADE_COMPLETED'') OR ' ||
    substring(prior FROM 8);
END $$;
