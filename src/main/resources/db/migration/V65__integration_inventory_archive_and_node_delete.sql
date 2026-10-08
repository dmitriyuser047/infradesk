ALTER TABLE integration_inventory_object ADD COLUMN archived_at timestamptz;
ALTER TABLE integration_inventory_object ADD CONSTRAINT ck_inventory_archived_absent
  CHECK (archived_at IS NULL OR NOT is_active);
ALTER TABLE integration_action_execution DROP CONSTRAINT integration_action_execution_action_code_check;
ALTER TABLE integration_action_execution ADD CONSTRAINT integration_action_execution_action_code_check
  CHECK (action_code IN ('NODE_ENABLE','NODE_DISABLE','NODE_RESTART','NODE_DELETE'));

CREATE FUNCTION infradesk_inventory_mutation_busy(org uuid, integration_key uuid) RETURNS boolean LANGUAGE sql VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM integration_action_execution WHERE organization_id=org AND integration_id=integration_key AND status IN ('QUEUED','RUNNING'))
 OR EXISTS(SELECT 1 FROM integration_config_deployment WHERE organization_id=org AND integration_id=integration_key AND status IN ('QUEUED','RUNNING'))
 OR EXISTS(SELECT 1 FROM integration_config_rollout WHERE organization_id=org AND integration_id=integration_key AND status IN ('PREPARING','APPLYING','VERIFYING','ROLLBACK_APPLYING','ROLLBACK_VERIFYING'))
 OR EXISTS(SELECT 1 FROM remnawave_node_onboarding WHERE organization_id=org AND integration_id=integration_key AND state IN ('QUEUED','RUNNING'))
 OR EXISTS(SELECT 1 FROM remnawave_fleet_rollout WHERE organization_id=org AND integration_id=integration_key AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'))
 OR EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_run WHERE organization_id=org AND integration_id=integration_key AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'));
$$;

CREATE FUNCTION infradesk_inventory_archive_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.archived_at IS NULL OR NEW.archived_at IS NOT DISTINCT FROM OLD.archived_at THEN RETURN NEW; END IF;
 PERFORM id FROM integration WHERE id=NEW.integration_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF NEW.is_active OR infradesk_inventory_mutation_busy(NEW.organization_id,NEW.integration_id) OR
  EXISTS(SELECT 1 FROM integration_resource_binding WHERE organization_id=NEW.organization_id AND inventory_object_id=NEW.id) OR
  EXISTS(SELECT 1 FROM integration_desired_state WHERE organization_id=NEW.organization_id AND inventory_object_id=NEW.id) OR
  EXISTS(SELECT 1 FROM remnawave_fleet_membership WHERE organization_id=NEW.organization_id AND inventory_node_id=NEW.id AND removed_at IS NULL) THEN
  RAISE EXCEPTION 'INTEGRATION_INVENTORY_ARCHIVE_BLOCKED' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER inventory_archive_guard BEFORE UPDATE OF archived_at ON integration_inventory_object
 FOR EACH ROW EXECUTE FUNCTION infradesk_inventory_archive_guard();

CREATE FUNCTION infradesk_archived_inventory_reference_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM id FROM integration WHERE id=NEW.integration_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM integration_inventory_object WHERE organization_id=NEW.organization_id
  AND integration_id=NEW.integration_id AND id=NEW.inventory_object_id AND archived_at IS NOT NULL) THEN
  RAISE EXCEPTION 'INTEGRATION_OBJECT_ARCHIVED' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER archived_inventory_reference_guard BEFORE INSERT OR UPDATE ON integration_resource_binding
 FOR EACH ROW EXECUTE FUNCTION infradesk_archived_inventory_reference_guard();
CREATE TRIGGER archived_inventory_reference_guard BEFORE INSERT OR UPDATE OF inventory_object_id, integration_id, organization_id ON integration_desired_state
 FOR EACH ROW EXECUTE FUNCTION infradesk_archived_inventory_reference_guard();

-- Desired state must be explicitly retired before admitting a destructive Panel operation.
-- Serialize with the same integration lock used by onboarding, fleet admission and intent edits.
CREATE FUNCTION infradesk_panel_delete_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND (OLD.action_code='NODE_DELETE' OR NEW.action_code='NODE_DELETE') AND
   (NEW.action_code,NEW.organization_id,NEW.integration_id,NEW.inventory_object_id,NEW.external_id_snapshot,
    NEW.request_id,NEW.requested_by_user_id,NEW.source) IS DISTINCT FROM
   (OLD.action_code,OLD.organization_id,OLD.integration_id,OLD.inventory_object_id,OLD.external_id_snapshot,
    OLD.request_id,OLD.requested_by_user_id,OLD.source) THEN
   RAISE EXCEPTION 'INTEGRATION_NODE_DELETE_IDENTITY_IMMUTABLE' USING ERRCODE='23514';
 END IF;
 IF NEW.action_code<>'NODE_DELETE' OR NEW.status NOT IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
 PERFORM id FROM integration WHERE id=NEW.integration_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF TG_OP='INSERT' AND EXISTS(SELECT 1 FROM integration_action_execution WHERE organization_id=NEW.organization_id AND request_id=NEW.request_id) THEN RETURN NEW; END IF;
 IF TG_OP='INSERT' AND infradesk_inventory_mutation_busy(NEW.organization_id,NEW.integration_id) THEN
  RAISE EXCEPTION 'INTEGRATION_ACTION_ALREADY_RUNNING' USING ERRCODE='23514';
 END IF;
 IF NEW.source<>'MANUAL' OR EXISTS(SELECT 1 FROM integration_desired_state WHERE organization_id=NEW.organization_id
   AND integration_id=NEW.integration_id AND inventory_object_id=NEW.inventory_object_id) OR
   EXISTS(SELECT 1 FROM remnawave_fleet_membership WHERE organization_id=NEW.organization_id
   AND integration_id=NEW.integration_id AND inventory_node_id=NEW.inventory_object_id AND removed_at IS NULL) THEN
   RAISE EXCEPTION 'INTEGRATION_NODE_DELETE_REFERENCES_ACTIVE' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER integration_panel_delete_guard BEFORE INSERT OR UPDATE ON integration_action_execution
 FOR EACH ROW EXECUTE FUNCTION infradesk_panel_delete_guard();

-- Admission must be symmetric: a fleet/onboarding/configuration workflow cannot start
-- after the Panel deletion already owns its durable action slot.
CREATE FUNCTION infradesk_panel_delete_exclusion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME IN ('integration_config_deployment','integration_config_rollout') THEN
  IF NEW.status NOT IN ('QUEUED','RUNNING','PREPARING','APPLYING','VERIFYING','ROLLBACK_APPLYING','ROLLBACK_VERIFYING') THEN RETURN NEW; END IF;
 ELSIF TG_TABLE_NAME IN ('remnawave_node_onboarding','remnawave_fleet_rollout','remnawave_fleet_upgrade_run') THEN
  IF NEW.state NOT IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') THEN RETURN NEW; END IF;
 END IF;
 PERFORM id FROM integration WHERE id=NEW.integration_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM integration_action_execution WHERE organization_id=NEW.organization_id
   AND integration_id=NEW.integration_id AND action_code='NODE_DELETE' AND status IN ('QUEUED','RUNNING')) THEN
   RAISE EXCEPTION 'INTEGRATION_ACTION_ALREADY_RUNNING' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER panel_delete_exclusion BEFORE INSERT OR UPDATE ON remnawave_node_onboarding
 FOR EACH ROW EXECUTE FUNCTION infradesk_panel_delete_exclusion();
CREATE TRIGGER panel_delete_exclusion BEFORE INSERT OR UPDATE ON remnawave_fleet_rollout
 FOR EACH ROW EXECUTE FUNCTION infradesk_panel_delete_exclusion();
CREATE TRIGGER panel_delete_exclusion BEFORE INSERT OR UPDATE ON remnawave_fleet_upgrade_run
 FOR EACH ROW EXECUTE FUNCTION infradesk_panel_delete_exclusion();
CREATE TRIGGER panel_delete_exclusion BEFORE INSERT OR UPDATE ON integration_config_deployment
 FOR EACH ROW EXECUTE FUNCTION infradesk_panel_delete_exclusion();
CREATE TRIGGER panel_delete_exclusion BEFORE INSERT OR UPDATE ON integration_config_rollout
 FOR EACH ROW EXECUTE FUNCTION infradesk_panel_delete_exclusion();
CREATE TRIGGER panel_delete_exclusion BEFORE INSERT OR UPDATE OF desired_state, inventory_object_id, integration_id, organization_id ON integration_desired_state
 FOR EACH ROW EXECUTE FUNCTION infradesk_panel_delete_exclusion();
CREATE TRIGGER panel_delete_exclusion BEFORE INSERT OR UPDATE OF inventory_node_id, resource_id, fleet_id, integration_id, removed_at ON remnawave_fleet_membership
 FOR EACH ROW EXECUTE FUNCTION infradesk_panel_delete_exclusion();

DO $$
DECLARE previous_check text;
BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous_check FROM pg_constraint
 WHERE conrelid='audit_event'::regclass AND conname='ck_audit_event_action';
 IF previous_check IS NULL THEN RAISE EXCEPTION 'AUDIT_ACTION_CONSTRAINT_MISSING'; END IF;
 ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
 EXECUTE 'ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (' || substring(previous_check FROM 7) ||
  ' OR action=''INTEGRATION_INVENTORY_ARCHIVED'')';
END $$;
