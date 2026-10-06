-- Admission takes integration -> fleet/resource -> workflow rows. Remote I/O never holds these locks.
CREATE FUNCTION infradesk_remnawave_admission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE resource uuid; node_external text; member record;
BEGIN
  IF TG_TABLE_NAME='integration_action_execution' THEN
    IF NEW.status NOT IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND OLD.status IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
  ELSE
    IF NEW.state NOT IN ('PLANNED','QUEUED','RUNNING','PAUSED','ROLLING_BACK') THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND OLD.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') THEN RETURN NEW; END IF;
  END IF;
  PERFORM id FROM integration WHERE organization_id=NEW.organization_id AND id=NEW.integration_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'INTEGRATION_NOT_FOUND' USING ERRCODE='23514'; END IF;
  IF TG_TABLE_NAME='integration_action_execution' THEN
    SELECT external_id INTO node_external FROM integration_inventory_object WHERE id=NEW.inventory_object_id
      AND organization_id=NEW.organization_id AND integration_id=NEW.integration_id AND object_type='NODE';
    FOR resource IN
      SELECT b.resource_id FROM integration_resource_binding b WHERE b.organization_id=NEW.organization_id
        AND b.integration_id=NEW.integration_id AND b.inventory_object_id=NEW.inventory_object_id
      UNION SELECT r.resource_id FROM remnawave_node_onboarding r WHERE r.organization_id=NEW.organization_id
        AND r.integration_id=NEW.integration_id AND r.state<>'PLANNED' AND
        (r.external_node_id::text=node_external OR r.input_snapshot->'recovery'->>'previousExternalNodeId'=node_external)
      ORDER BY 1
    LOOP
      PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':'||resource::text,1));
      IF EXISTS(SELECT 1 FROM remnawave_node_onboarding r WHERE r.organization_id=NEW.organization_id
        AND r.resource_id=resource AND r.state IN ('QUEUED','RUNNING')) THEN
        RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
      END IF;
    END LOOP;
  ELSIF TG_TABLE_NAME='remnawave_node_onboarding' AND NEW.state IN ('QUEUED','RUNNING') THEN
    FOR member IN SELECT m.fleet_id FROM remnawave_fleet_membership m WHERE m.organization_id=NEW.organization_id
      AND m.integration_id=NEW.integration_id AND m.resource_id=NEW.resource_id AND m.removed_at IS NULL ORDER BY m.fleet_id
    LOOP
      PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':fleet:'||member.fleet_id::text,2));
      IF EXISTS(SELECT 1 FROM remnawave_fleet_rollout WHERE fleet_id=member.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'))
        OR EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_run WHERE fleet_id=member.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) THEN
        RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
      END IF;
    END LOOP;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':'||NEW.resource_id::text,1));
    IF EXISTS(SELECT 1 FROM integration_action_execution a WHERE a.organization_id=NEW.organization_id
      AND a.status IN ('QUEUED','RUNNING') AND (
        EXISTS(SELECT 1 FROM integration_resource_binding b WHERE b.organization_id=NEW.organization_id
          AND b.inventory_object_id=a.inventory_object_id AND b.resource_id=NEW.resource_id)
        OR a.integration_id=NEW.integration_id AND (a.external_id_snapshot=NEW.external_node_id::text
          OR a.external_id_snapshot=NEW.input_snapshot->'recovery'->>'previousExternalNodeId'))) THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
    END IF;
    IF NEW.input_snapshot->'recovery'->>'action' IN ('RECREATE','DELETE_RECREATE') AND
      (SELECT count(*) FROM remnawave_fleet_membership WHERE organization_id=NEW.organization_id
        AND integration_id=NEW.integration_id AND resource_id=NEW.resource_id AND removed_at IS NULL)>1 THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW' USING ERRCODE='23514';
    END IF;
  ELSIF TG_TABLE_NAME IN ('remnawave_fleet_rollout','remnawave_fleet_upgrade_run') AND
    NEW.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') THEN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':fleet:'||NEW.fleet_id::text,2));
    IF EXISTS(SELECT 1 FROM remnawave_fleet_membership m JOIN remnawave_node_onboarding r
      ON r.organization_id=m.organization_id AND r.integration_id=m.integration_id AND r.resource_id=m.resource_id
      WHERE m.organization_id=NEW.organization_id AND m.integration_id=NEW.integration_id AND m.fleet_id=NEW.fleet_id
        AND m.removed_at IS NULL AND r.state IN ('QUEUED','RUNNING')) THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
-- First alphabetically: acquire integration ownership before existing resource/config guards.
CREATE TRIGGER aaa_onboarding_admission BEFORE INSERT OR UPDATE OF state ON remnawave_node_onboarding
  FOR EACH ROW EXECUTE FUNCTION infradesk_remnawave_admission();
CREATE TRIGGER aaa_action_onboarding_admission BEFORE INSERT OR UPDATE OF status ON integration_action_execution
  FOR EACH ROW EXECUTE FUNCTION infradesk_remnawave_admission();
CREATE TRIGGER aaa_fleet_rollout_admission BEFORE INSERT OR UPDATE OF state ON remnawave_fleet_rollout
  FOR EACH ROW EXECUTE FUNCTION infradesk_remnawave_admission();
CREATE TRIGGER aaa_fleet_upgrade_admission BEFORE INSERT OR UPDATE OF state ON remnawave_fleet_upgrade_run
  FOR EACH ROW EXECUTE FUNCTION infradesk_remnawave_admission();

CREATE TABLE remnawave_fleet_membership_replacement (
  onboarding_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  integration_id uuid NOT NULL,
  membership_id uuid NOT NULL,
  fleet_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  previous_inventory_node_id uuid NOT NULL,
  inventory_node_id uuid NOT NULL,
  previous_version bigint NOT NULL,
  version bigint NOT NULL CHECK(version=previous_version+1),
  replaced_at timestamptz NOT NULL,
  PRIMARY KEY(onboarding_id,membership_id),
  FOREIGN KEY(onboarding_id,organization_id) REFERENCES remnawave_node_onboarding(id,organization_id),
  FOREIGN KEY(membership_id,fleet_id,organization_id) REFERENCES remnawave_fleet_membership(id,fleet_id,organization_id),
  FOREIGN KEY(previous_inventory_node_id,integration_id,organization_id) REFERENCES integration_inventory_object(id,integration_id,organization_id),
  FOREIGN KEY(inventory_node_id,integration_id,organization_id) REFERENCES integration_inventory_object(id,integration_id,organization_id),
  FOREIGN KEY(resource_id,organization_id) REFERENCES resource(id,organization_id)
);
CREATE FUNCTION infradesk_membership_replacement_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'REMNAWAVE_FLEET_MEMBERSHIP_HISTORY_IMMUTABLE' USING ERRCODE='23514'; END $$;
CREATE TRIGGER remnawave_membership_replacement_immutable BEFORE UPDATE OR DELETE ON remnawave_fleet_membership_replacement
  FOR EACH ROW EXECUTE FUNCTION infradesk_membership_replacement_immutable();

CREATE FUNCTION infradesk_membership_lifecycle_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r remnawave_node_onboarding; approved uuid; token uuid;
BEGIN
  IF TG_OP='UPDATE' AND (NEW.inventory_node_id,NEW.resource_id,NEW.fleet_id,NEW.integration_id,NEW.removed_at)
    IS NOT DISTINCT FROM (OLD.inventory_node_id,OLD.resource_id,OLD.fleet_id,OLD.integration_id,OLD.removed_at) THEN RETURN NEW; END IF;
  PERFORM id FROM integration WHERE id=NEW.integration_id AND organization_id=NEW.organization_id FOR UPDATE;
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':fleet:'||NEW.fleet_id::text,2));
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':'||NEW.resource_id::text,1));
  IF EXISTS(SELECT 1 FROM remnawave_fleet_rollout WHERE fleet_id=NEW.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'))
    OR EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_run WHERE fleet_id=NEW.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
  END IF;
  SELECT * INTO r FROM remnawave_node_onboarding WHERE organization_id=NEW.organization_id AND resource_id=NEW.resource_id
    AND state IN ('QUEUED','RUNNING');
  IF FOUND THEN
    approved:=nullif(current_setting('infradesk.onboarding_replacement_id',true),'')::uuid;
    token:=nullif(current_setting('infradesk.onboarding_replacement_token',true),'')::uuid;
    IF TG_OP<>'UPDATE' OR approved IS DISTINCT FROM r.id OR token IS DISTINCT FROM r.claim_token
      OR r.state<>'RUNNING' OR r.phase<>'BIND_RESOURCE' OR r.claim_deadline<=clock_timestamp()
      OR r.integration_id<>NEW.integration_id OR NEW.organization_id<>OLD.organization_id
      OR NEW.integration_id<>OLD.integration_id OR NEW.fleet_id<>OLD.fleet_id OR NEW.resource_id<>OLD.resource_id
      OR NEW.removed_at IS DISTINCT FROM OLD.removed_at OR r.input_snapshot->'recovery'->>'action' NOT IN ('RECREATE','DELETE_RECREATE')
      OR NOT EXISTS(SELECT 1 FROM integration_inventory_object WHERE id=OLD.inventory_node_id
        AND NOT is_active AND external_id=r.input_snapshot->'recovery'->>'previousExternalNodeId')
      OR NOT EXISTS(SELECT 1 FROM integration_inventory_object WHERE id=NEW.inventory_node_id
        AND is_active AND external_id=r.external_node_id::text) THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
    END IF;
  ELSIF TG_OP='UPDATE' AND NEW.inventory_node_id<>OLD.inventory_node_id THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_REPLACEMENT_REQUIRES_ONBOARDING' USING ERRCODE='23514';
  END IF;
  IF NEW.removed_at IS NULL AND EXISTS(SELECT 1 FROM remnawave_fleet_membership WHERE organization_id=NEW.organization_id
    AND integration_id=NEW.integration_id AND resource_id=NEW.resource_id AND removed_at IS NULL AND id<>NEW.id) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_RESOURCE_ALREADY_MEMBER' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER aaa_membership_lifecycle_guard BEFORE INSERT OR UPDATE OF inventory_node_id,resource_id,fleet_id,integration_id,removed_at
  ON remnawave_fleet_membership FOR EACH ROW EXECUTE FUNCTION infradesk_membership_lifecycle_guard();

CREATE FUNCTION infradesk_onboarding_replace_membership(org uuid, run uuid, token uuid, node uuid, at timestamptz)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE r remnawave_node_onboarding; m remnawave_fleet_membership; old_node uuid; integration_key uuid;
BEGIN
  SELECT integration_id INTO integration_key FROM remnawave_node_onboarding WHERE id=run AND organization_id=org;
  PERFORM id FROM integration WHERE id=integration_key AND organization_id=org FOR UPDATE;
  SELECT * INTO r FROM remnawave_node_onboarding WHERE id=run AND organization_id=org FOR UPDATE;
  IF NOT FOUND OR r.state<>'RUNNING' OR r.phase<>'BIND_RESOURCE' OR r.claim_token IS DISTINCT FROM token
    OR r.claim_deadline<=greatest(at,clock_timestamp()) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_LEASE_LOST' USING ERRCODE='23514';
  END IF;
  IF r.input_snapshot->'recovery'->>'action' NOT IN ('RECREATE','DELETE_RECREATE') OR r.input_snapshot->'recovery' IS NULL
    OR r.input_snapshot->'recovery'='null'::jsonb THEN RETURN; END IF;
  IF NOT EXISTS(SELECT 1 FROM integration_inventory_object o JOIN integration_resource_binding b ON b.inventory_object_id=o.id
    WHERE o.id=node AND o.organization_id=org AND o.integration_id=r.integration_id AND o.object_type='NODE'
      AND o.is_active AND o.external_id=r.external_node_id::text AND b.resource_id=r.resource_id AND b.organization_id=org) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514';
  END IF;
  SELECT id INTO old_node FROM integration_inventory_object WHERE organization_id=org AND integration_id=r.integration_id
    AND object_type='NODE' AND external_id=r.input_snapshot->'recovery'->>'previousExternalNodeId';
  IF old_node IS NULL THEN RETURN; END IF;
  IF EXISTS(SELECT 1 FROM integration_inventory_object WHERE id=old_node AND is_active) OR
    EXISTS(SELECT 1 FROM integration_resource_binding WHERE organization_id=org AND integration_id=r.integration_id
      AND inventory_object_id=old_node AND resource_id<>r.resource_id) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514';
  END IF;
  SELECT * INTO m FROM remnawave_fleet_membership WHERE organization_id=org AND integration_id=r.integration_id
    AND resource_id=r.resource_id AND inventory_node_id=old_node AND removed_at IS NULL;
  IF NOT FOUND THEN
    IF EXISTS(SELECT 1 FROM remnawave_fleet_membership WHERE organization_id=org AND integration_id=r.integration_id
      AND resource_id=r.resource_id AND removed_at IS NULL AND inventory_node_id<>node) THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514';
    END IF;
    RETURN;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(org::text||':fleet:'||m.fleet_id::text,2));
  PERFORM pg_advisory_xact_lock(hashtextextended(org::text||':'||r.resource_id::text,1));
  PERFORM id FROM remnawave_fleet WHERE id=m.fleet_id AND organization_id=org AND NOT archived FOR UPDATE;
  IF NOT FOUND OR EXISTS(SELECT 1 FROM remnawave_fleet_rollout WHERE fleet_id=m.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'))
    OR EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_run WHERE fleet_id=m.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
  END IF;
  SELECT * INTO m FROM remnawave_fleet_membership WHERE id=m.id AND removed_at IS NULL FOR UPDATE;
  IF NOT FOUND OR m.inventory_node_id<>old_node THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514'; END IF;
  DELETE FROM remnawave_fleet_node_assessment WHERE membership_id=m.id;
  DELETE FROM remnawave_node_image_observation WHERE membership_id=m.id;
  PERFORM set_config('infradesk.onboarding_replacement_id',run::text,true);
  PERFORM set_config('infradesk.onboarding_replacement_token',token::text,true);
  UPDATE remnawave_fleet_membership SET inventory_node_id=node,version=version+1,next_check_at=at,
    claimed_by=NULL,claim_token=NULL,claim_deadline=NULL WHERE id=m.id;
  INSERT INTO remnawave_fleet_membership_replacement VALUES(run,org,r.integration_id,m.id,m.fleet_id,r.resource_id,
    old_node,node,m.version,m.version+1,at);
END $$;
