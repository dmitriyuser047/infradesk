-- Observer leases update a membership row before BEFORE UPDATE triggers execute. Taking an
-- integration row lock there reverses replacement's integration -> membership order. Lease-only
-- progress carries no new ownership intent; new claims check active identity without that lock,
-- and the worker revalidates active evidence before any remote read. Ownership changes retain
-- V55's serialized admission. Existing claims may release after a tombstone.
CREATE OR REPLACE FUNCTION infradesk_integration_metadata_admission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE org_id uuid; integration_id uuid;
BEGIN
  IF TG_TABLE_NAME='remnawave_fleet_membership' AND TG_OP='UPDATE' THEN
    IF (NEW.id,NEW.organization_id,NEW.integration_id,NEW.fleet_id,NEW.inventory_node_id,
        NEW.resource_id,NEW.version,NEW.created_by,NEW.created_at,NEW.removed_at)
      IS NOT DISTINCT FROM
       (OLD.id,OLD.organization_id,OLD.integration_id,OLD.fleet_id,OLD.inventory_node_id,
        OLD.resource_id,OLD.version,OLD.created_by,OLD.created_at,OLD.removed_at) THEN
      IF NEW.claim_token IS DISTINCT FROM OLD.claim_token AND NEW.claim_token IS NOT NULL THEN
        PERFORM 1 FROM integration WHERE id=NEW.integration_id AND organization_id=NEW.organization_id
          AND deleted_at IS NULL;
        IF NOT FOUND THEN RAISE EXCEPTION 'INTEGRATION_NOT_FOUND' USING ERRCODE='23514'; END IF;
      END IF;
      RETURN NEW;
    END IF;
  END IF;
  IF TG_TABLE_NAME='remnawave_fleet' OR TG_TABLE_NAME='remnawave_fleet_membership' OR
     TG_TABLE_NAME='remnawave_fleet_release_revision' OR
     TG_TABLE_NAME='integration_config_profile_binding' OR
     TG_TABLE_NAME='remnawave_fleet_membership_replacement' THEN
    org_id := NEW.organization_id;
    integration_id := NEW.integration_id;
  ELSIF TG_TABLE_NAME='remnawave_fleet_revision' OR TG_TABLE_NAME='remnawave_fleet_release_policy' THEN
    org_id := NEW.organization_id;
    SELECT f.integration_id INTO integration_id FROM remnawave_fleet f
      WHERE f.id=NEW.fleet_id AND f.organization_id=NEW.organization_id;
  END IF;
  PERFORM 1 FROM integration WHERE organization_id=org_id AND id=integration_id
    AND deleted_at IS NULL FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'INTEGRATION_NOT_FOUND' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;

-- Desired-state batches share the integration lock while claiming disjoint intent rows. Pure
-- lease/progress updates must not upgrade that lock; action creation acquires it exclusively
-- before locking any intent or inventory row and retains reciprocal onboarding admission.
CREATE OR REPLACE FUNCTION infradesk_integration_active_admission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE entering boolean := false;
BEGIN
  IF TG_TABLE_NAME='integration_action_execution' THEN
    entering := NEW.status IN ('QUEUED','RUNNING') AND
      (TG_OP='INSERT' OR OLD.status NOT IN ('QUEUED','RUNNING'));
  ELSIF TG_TABLE_NAME='integration_config_deployment' THEN
    entering := NEW.status IN ('QUEUED','RUNNING') AND
      (TG_OP='INSERT' OR OLD.status NOT IN ('QUEUED','RUNNING'));
  ELSIF TG_TABLE_NAME='integration_config_rollout' THEN
    entering := NEW.status IN ('PREPARING','APPLYING','VERIFYING','ROLLBACK_APPLYING','ROLLBACK_VERIFYING') AND
      (TG_OP='INSERT' OR OLD.status NOT IN ('PREPARING','APPLYING','VERIFYING','ROLLBACK_APPLYING','ROLLBACK_VERIFYING'));
  ELSIF TG_TABLE_NAME='integration_sync_session' THEN
    entering := NEW.status='RUNNING' AND (TG_OP='INSERT' OR OLD.status<>'RUNNING');
  ELSIF TG_TABLE_NAME='integration_desired_state' THEN
    IF TG_OP='UPDATE' THEN
      IF (NEW.id,NEW.organization_id,NEW.integration_id,NEW.inventory_object_id,NEW.desired_state,
          NEW.version,NEW.set_by_user_id,NEW.created_at,NEW.updated_at)
        IS NOT DISTINCT FROM
         (OLD.id,OLD.organization_id,OLD.integration_id,OLD.inventory_object_id,OLD.desired_state,
          OLD.version,OLD.set_by_user_id,OLD.created_at,OLD.updated_at) THEN
        IF NEW.claim_token IS DISTINCT FROM OLD.claim_token AND NEW.claim_token IS NOT NULL THEN
          PERFORM 1 FROM integration WHERE organization_id=NEW.organization_id AND id=NEW.integration_id
            AND deleted_at IS NULL;
          IF NOT FOUND THEN RAISE EXCEPTION 'INTEGRATION_NOT_FOUND' USING ERRCODE='23514'; END IF;
        END IF;
        RETURN NEW;
      END IF;
    END IF;
    entering := true;
  ELSIF TG_TABLE_NAME='remnawave_node_onboarding' THEN
    entering := TG_OP='INSERT' OR (NEW.state IN ('QUEUED','RUNNING') AND OLD.state NOT IN ('QUEUED','RUNNING'));
  ELSIF TG_TABLE_NAME='remnawave_fleet_rollout' THEN
    entering := TG_OP='INSERT' OR (NEW.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') AND
      OLD.state NOT IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'));
  ELSIF TG_TABLE_NAME='remnawave_fleet_upgrade_run' THEN
    entering := TG_OP='INSERT' OR (NEW.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') AND
      OLD.state NOT IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'));
  END IF;
  IF NOT entering THEN RETURN NEW; END IF;
  PERFORM 1 FROM integration WHERE organization_id=NEW.organization_id AND id=NEW.integration_id
    AND deleted_at IS NULL FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'INTEGRATION_NOT_FOUND' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
