-- Additive recovery phases. Existing snapshots and terminal histories are unchanged.
ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_node_onboarding_phase_check;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_node_onboarding_phase_check CHECK (phase IN
 ('VALIDATE','PREPARE_SERVER','DELETE_NODE','CONFIRM_NODE_DELETED','RETIRE_LOCAL_NODE','CREATE_NODE','GET_INSTALLATION_DATA',
 'CONFIGURE_NODE_FIREWALL','INSTALL_NODE','START_NODE','VERIFY_LOCAL_NODE','WAIT_FOR_PANEL','SYNC_INVENTORY',
 'BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY'));
ALTER TABLE remnawave_node_onboarding_phase DROP CONSTRAINT remnawave_node_onboarding_phase_position_check;
ALTER TABLE remnawave_node_onboarding_phase ADD CONSTRAINT remnawave_node_onboarding_phase_position_check CHECK(position BETWEEN 0 AND 15);
ALTER TABLE remnawave_node_onboarding_phase DROP CONSTRAINT remnawave_node_onboarding_phase_check1;

-- The immutable snapshot selects the phase sequence. Arbitrary phases cannot be inserted.
CREATE FUNCTION infradesk_onboarding_phase_sequence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected text[]; snapshot jsonb;
BEGIN
  SELECT input_snapshot INTO snapshot FROM remnawave_node_onboarding WHERE id=NEW.run_id;
  IF snapshot->'recovery'->>'action'='DELETE_RECREATE' THEN
    expected:=ARRAY['VALIDATE','PREPARE_SERVER','DELETE_NODE','CONFIRM_NODE_DELETED','RETIRE_LOCAL_NODE','CREATE_NODE','GET_INSTALLATION_DATA',
      'CONFIGURE_NODE_FIREWALL','INSTALL_NODE','START_NODE','VERIFY_LOCAL_NODE','WAIT_FOR_PANEL','SYNC_INVENTORY',
      'BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY'];
  ELSIF snapshot->'recovery'->>'action'='RECREATE' THEN
    expected:=ARRAY['VALIDATE','PREPARE_SERVER','RETIRE_LOCAL_NODE','CREATE_NODE','GET_INSTALLATION_DATA',
      'CONFIGURE_NODE_FIREWALL','INSTALL_NODE','START_NODE','VERIFY_LOCAL_NODE','WAIT_FOR_PANEL','SYNC_INVENTORY',
      'BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY'];
  ELSE
    expected:=ARRAY['VALIDATE','PREPARE_SERVER','CREATE_NODE','GET_INSTALLATION_DATA','CONFIGURE_NODE_FIREWALL',
      'INSTALL_NODE','START_NODE','VERIFY_LOCAL_NODE','WAIT_FOR_PANEL','SYNC_INVENTORY','BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY'];
  END IF;
  IF NEW.phase IS DISTINCT FROM expected[NEW.position+1] THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_PHASE_INVALID' USING ERRCODE='23514';
  END IF;
  IF TG_OP='UPDATE' AND (NEW.run_id<>OLD.run_id OR NEW.phase<>OLD.phase OR NEW.position<>OLD.position) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_PHASE_IMMUTABLE';
  END IF;
  IF TG_OP='UPDATE' AND EXISTS(SELECT 1 FROM remnawave_node_onboarding WHERE id=OLD.run_id AND state IN ('SUCCEEDED','FAILED','UNKNOWN'))
    AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_TERMINAL'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_onboarding_phase_sequence BEFORE INSERT OR UPDATE ON remnawave_node_onboarding_phase
  FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_phase_sequence();

CREATE OR REPLACE FUNCTION infradesk_onboarding_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.input_snapshot IS DISTINCT FROM OLD.input_snapshot OR NEW.organization_id<>OLD.organization_id
    OR NEW.integration_id<>OLD.integration_id OR NEW.resource_id<>OLD.resource_id OR NEW.created_by<>OLD.created_by
    OR NEW.created_at<>OLD.created_at THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_SNAPSHOT_IMMUTABLE'; END IF;
  IF OLD.state IN ('SUCCEEDED','FAILED','UNKNOWN') AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_TERMINAL'; END IF;
  IF OLD.external_node_id IS NOT NULL AND NEW.external_node_id IS DISTINCT FROM OLD.external_node_id THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_IDENTITY_IMMUTABLE'; END IF;
  IF NEW.state='SUCCEEDED' AND (NEW.phase<>'FINAL_VERIFY' OR
    (SELECT count(*) FROM remnawave_node_onboarding_phase WHERE run_id=NEW.id AND state='SUCCEEDED')<>CASE WHEN NEW.input_snapshot->'recovery'->>'action'='DELETE_RECREATE' THEN 16 WHEN NEW.input_snapshot->'recovery'->>'action'='RECREATE' THEN 14 ELSE 13 END) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_VERIFICATION_REQUIRED'; END IF;
  RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION infradesk_onboarding_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE binding_row integration_resource_binding; onboarding remnawave_node_onboarding; external_id text; source_integration uuid;
BEGIN
  IF TG_OP='DELETE' THEN binding_row := OLD; ELSE binding_row := NEW; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(binding_row.organization_id::text || ':' || binding_row.resource_id::text,1));
  SELECT * INTO onboarding FROM remnawave_node_onboarding WHERE organization_id=binding_row.organization_id
    AND resource_id=binding_row.resource_id AND state IN ('QUEUED','RUNNING');
  IF FOUND THEN
    SELECT o.external_id,o.integration_id INTO external_id,source_integration FROM integration_inventory_object o WHERE o.id=binding_row.inventory_object_id;
    IF TG_OP='DELETE' AND onboarding.state='RUNNING' AND onboarding.phase='BIND_RESOURCE'
      AND onboarding.claim_deadline>clock_timestamp()
      AND onboarding.input_snapshot->'recovery'->>'action' IN ('RECREATE','DELETE_RECREATE')
      AND external_id=onboarding.input_snapshot->'recovery'->>'previousExternalNodeId'
      AND source_integration=onboarding.integration_id
      AND EXISTS(SELECT 1 FROM integration_inventory_object o WHERE o.id=binding_row.inventory_object_id
        AND o.organization_id=onboarding.organization_id AND NOT o.is_active) THEN RETURN OLD; END IF;
    IF TG_OP='DELETE' OR onboarding.external_node_id IS NULL OR external_id IS DISTINCT FROM onboarding.external_node_id::text
      OR source_integration IS DISTINCT FROM onboarding.integration_id THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514';
    END IF;
  END IF;
  IF TG_OP='UPDATE' AND OLD.resource_id IS DISTINCT FROM NEW.resource_id THEN
    PERFORM pg_advisory_xact_lock(hashtextextended(OLD.organization_id::text || ':' || OLD.resource_id::text,1));
    IF EXISTS(SELECT 1 FROM remnawave_node_onboarding WHERE organization_id=OLD.organization_id AND resource_id=OLD.resource_id
      AND state IN ('QUEUED','RUNNING')) THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;

-- Retain approved execution history; only expired unapproved drafts may be removed.
CREATE FUNCTION infradesk_onboarding_history_delete_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME='remnawave_node_onboarding' THEN
    IF OLD.state<>'PLANNED' THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_HISTORY_IMMUTABLE'; END IF;
  ELSIF EXISTS(SELECT 1 FROM remnawave_node_onboarding WHERE id=OLD.run_id
    AND state IN ('SUCCEEDED','FAILED','UNKNOWN')) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_HISTORY_IMMUTABLE';
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER remnawave_onboarding_history_delete_guard BEFORE DELETE ON remnawave_node_onboarding
  FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_history_delete_guard();
CREATE TRIGGER remnawave_onboarding_phase_delete_guard BEFORE DELETE ON remnawave_node_onboarding_phase
  FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_history_delete_guard();
