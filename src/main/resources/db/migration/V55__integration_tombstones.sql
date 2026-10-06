-- Integration deletion retains its durable identity and all historical foreign keys. Credentials
-- are removed in the same application transaction that transitions the row to this tombstone.
ALTER TABLE integration ALTER COLUMN secret_id DROP NOT NULL;
ALTER TABLE integration ADD COLUMN deleted_at timestamptz;
ALTER TABLE integration ADD CONSTRAINT ck_integration_tombstone_credential CHECK (
  (deleted_at IS NULL AND secret_id IS NOT NULL) OR
  (deleted_at IS NOT NULL AND secret_id IS NULL AND NOT enabled AND NOT caddy_api_key_configured)
);

DROP INDEX uq_integration_organization_lower_name;
CREATE UNIQUE INDEX uq_integration_organization_lower_name
  ON integration (organization_id, lower(name)) WHERE deleted_at IS NULL;

CREATE FUNCTION infradesk_integration_tombstone_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'INTEGRATION_TOMBSTONE_IMMUTABLE' USING ERRCODE = '23514';
  END IF;
  IF OLD.deleted_at IS NOT NULL THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION 'INTEGRATION_TOMBSTONE_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.deleted_at IS NOT NULL THEN
    IF NEW.secret_id IS NOT NULL OR NEW.enabled OR NEW.caddy_api_key_configured OR
      (NEW.id, NEW.organization_id, NEW.name, NEW.provider_type, NEW.base_url, NEW.created_at, NEW.management_mode)
        IS DISTINCT FROM
      (OLD.id, OLD.organization_id, OLD.name, OLD.provider_type, OLD.base_url, OLD.created_at, OLD.management_mode) THEN
      RAISE EXCEPTION 'INTEGRATION_TOMBSTONE_INVALID' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM integration_action_execution WHERE organization_id=OLD.organization_id
      AND integration_id=OLD.id AND status IN ('QUEUED','RUNNING')) OR
      EXISTS (SELECT 1 FROM integration_config_deployment WHERE organization_id=OLD.organization_id
      AND integration_id=OLD.id AND status IN ('QUEUED','RUNNING')) OR
      EXISTS (SELECT 1 FROM integration_config_rollout WHERE organization_id=OLD.organization_id
      AND integration_id=OLD.id AND status IN ('PREPARING','APPLYING','VERIFYING','ROLLBACK_APPLYING','ROLLBACK_VERIFYING')) OR
      EXISTS (SELECT 1 FROM remnawave_node_onboarding WHERE organization_id=OLD.organization_id
      AND integration_id=OLD.id AND state IN ('QUEUED','RUNNING')) OR
      EXISTS (SELECT 1 FROM remnawave_fleet_rollout WHERE organization_id=OLD.organization_id
      AND integration_id=OLD.id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) OR
      EXISTS (SELECT 1 FROM remnawave_fleet_upgrade_run WHERE organization_id=OLD.organization_id
      AND integration_id=OLD.id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) OR
      EXISTS (SELECT 1 FROM integration_sync_session WHERE organization_id=OLD.organization_id
      AND integration_id=OLD.id AND status='RUNNING') OR
      EXISTS (SELECT 1 FROM integration_sync_state WHERE organization_id=OLD.organization_id
      AND integration_id=OLD.id AND claim_until > clock_timestamp()) OR
      EXISTS (SELECT 1 FROM integration_desired_state WHERE organization_id=OLD.organization_id
      AND integration_id=OLD.id AND claim_until > clock_timestamp()) THEN
      RAISE EXCEPTION 'INTEGRATION_ACTION_ALREADY_RUNNING' USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER integration_tombstone_guard BEFORE UPDATE ON integration
  FOR EACH ROW EXECUTE FUNCTION infradesk_integration_tombstone_guard();
CREATE TRIGGER integration_no_physical_delete BEFORE DELETE ON integration
  FOR EACH ROW EXECUTE FUNCTION infradesk_integration_tombstone_guard();

-- Admission serializes on the integration row, matching V54. Deletion takes the same row lock,
-- so either admission commits first and blocks deletion, or deletion commits first and admission
-- observes the tombstone.
CREATE FUNCTION infradesk_integration_active_admission() RETURNS trigger LANGUAGE plpgsql AS $$
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
    entering := true;
  ELSIF TG_TABLE_NAME='remnawave_node_onboarding' THEN
    entering := TG_OP='INSERT' OR (NEW.state IN ('QUEUED','RUNNING') AND
      OLD.state NOT IN ('QUEUED','RUNNING'));
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

CREATE TRIGGER aab_integration_action_tombstone_admission BEFORE INSERT OR UPDATE OF status
  ON integration_action_execution FOR EACH ROW EXECUTE FUNCTION infradesk_integration_active_admission();
CREATE TRIGGER aab_integration_config_deploy_tombstone_admission BEFORE INSERT OR UPDATE OF status
  ON integration_config_deployment FOR EACH ROW EXECUTE FUNCTION infradesk_integration_active_admission();
CREATE TRIGGER aab_integration_config_rollout_tombstone_admission BEFORE INSERT OR UPDATE OF status
  ON integration_config_rollout FOR EACH ROW EXECUTE FUNCTION infradesk_integration_active_admission();
CREATE TRIGGER aab_integration_sync_session_tombstone_admission BEFORE INSERT OR UPDATE OF status
  ON integration_sync_session FOR EACH ROW EXECUTE FUNCTION infradesk_integration_active_admission();
CREATE TRIGGER aab_integration_desired_state_tombstone_admission BEFORE INSERT OR UPDATE
  ON integration_desired_state FOR EACH ROW EXECUTE FUNCTION infradesk_integration_active_admission();
CREATE TRIGGER aab_onboarding_tombstone_admission BEFORE INSERT OR UPDATE OF state
  ON remnawave_node_onboarding FOR EACH ROW EXECUTE FUNCTION infradesk_integration_active_admission();
CREATE TRIGGER aab_fleet_rollout_tombstone_admission BEFORE INSERT OR UPDATE OF state
  ON remnawave_fleet_rollout FOR EACH ROW EXECUTE FUNCTION infradesk_integration_active_admission();
CREATE TRIGGER aab_fleet_upgrade_tombstone_admission BEFORE INSERT OR UPDATE OF state
  ON remnawave_fleet_upgrade_run FOR EACH ROW EXECUTE FUNCTION infradesk_integration_active_admission();

-- Desired-state and binding metadata are also admission points. They serialize on the same
-- integration row so that a concurrent tombstone cannot race creation or promotion.
CREATE FUNCTION infradesk_integration_metadata_admission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE org_id uuid; integration_id uuid;
BEGIN
  IF TG_TABLE_NAME='remnawave_fleet' OR TG_TABLE_NAME='remnawave_fleet_membership' OR
     TG_TABLE_NAME='remnawave_fleet_release_revision' OR
     TG_TABLE_NAME='integration_config_profile_binding' OR
     TG_TABLE_NAME='remnawave_fleet_membership_replacement' THEN
    org_id := NEW.organization_id;
    integration_id := NEW.integration_id;
  ELSIF TG_TABLE_NAME='remnawave_fleet_revision' THEN
    org_id := NEW.organization_id;
    SELECT f.integration_id INTO integration_id FROM remnawave_fleet f
      WHERE f.id=NEW.fleet_id AND f.organization_id=NEW.organization_id;
  ELSIF TG_TABLE_NAME='remnawave_fleet_release_policy' THEN
    org_id := NEW.organization_id;
    SELECT f.integration_id INTO integration_id FROM remnawave_fleet f
      WHERE f.id=NEW.fleet_id AND f.organization_id=NEW.organization_id;
  END IF;
  PERFORM 1 FROM integration WHERE organization_id=org_id AND id=integration_id
    AND deleted_at IS NULL FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'INTEGRATION_NOT_FOUND' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER aab_integration_config_binding_tombstone_admission
  BEFORE INSERT OR UPDATE ON integration_config_profile_binding FOR EACH ROW
  EXECUTE FUNCTION infradesk_integration_metadata_admission();
CREATE TRIGGER aab_fleet_tombstone_admission BEFORE INSERT OR UPDATE ON remnawave_fleet
  FOR EACH ROW EXECUTE FUNCTION infradesk_integration_metadata_admission();
CREATE TRIGGER aab_fleet_revision_tombstone_admission BEFORE INSERT OR UPDATE ON remnawave_fleet_revision
  FOR EACH ROW EXECUTE FUNCTION infradesk_integration_metadata_admission();
CREATE TRIGGER aab_fleet_membership_tombstone_admission BEFORE INSERT OR UPDATE ON remnawave_fleet_membership
  FOR EACH ROW EXECUTE FUNCTION infradesk_integration_metadata_admission();
CREATE TRIGGER aab_fleet_release_revision_tombstone_admission
  BEFORE INSERT OR UPDATE ON remnawave_fleet_release_revision FOR EACH ROW
  EXECUTE FUNCTION infradesk_integration_metadata_admission();
CREATE TRIGGER aab_fleet_release_policy_tombstone_admission
  BEFORE INSERT OR UPDATE ON remnawave_fleet_release_policy FOR EACH ROW
  EXECUTE FUNCTION infradesk_integration_metadata_admission();
CREATE TRIGGER aab_fleet_membership_replacement_tombstone_admission
  BEFORE INSERT ON remnawave_fleet_membership_replacement FOR EACH ROW
  EXECUTE FUNCTION infradesk_integration_metadata_admission();
