-- Preserve immutable v1/v2 sequences; v3 skips retirement only after proven local absence.
ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_onboarding_lifecycle_version_check;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_onboarding_lifecycle_version_check
 CHECK (NOT (input_snapshot ? 'lifecycleVersion') OR input_snapshot->'lifecycleVersion' IN ('1'::jsonb,'2'::jsonb,'3'::jsonb));

CREATE OR REPLACE FUNCTION infradesk_onboarding_expected_phases(snapshot jsonb) RETURNS text[] LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE expected text[];
BEGIN
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
  IF coalesce((snapshot->>'lifecycleVersion')::int,1)>=2 AND snapshot->'recovery'->>'action' IN ('RECREATE','DELETE_RECREATE') THEN
    expected:=array_replace(expected,'RETIRE_LOCAL_NODE','RETIRE_NODE_FIREWALL');
    SELECT array_agg(p ORDER BY position) INTO expected FROM (
      SELECT p,ordinality::numeric AS position FROM unnest(expected) WITH ORDINALITY AS phases(p,ordinality)
      UNION ALL SELECT 'RETIRE_LOCAL_NODE',array_position(expected,'RETIRE_NODE_FIREWALL')::numeric+0.5
    ) ordered_phases;
  END IF;
  IF coalesce((snapshot->>'lifecycleVersion')::int,1)=3 AND snapshot->'recovery'->'localInstallation'->>'state'='ABSENT' THEN
    expected:=array_remove(array_remove(expected,'RETIRE_NODE_FIREWALL'),'RETIRE_LOCAL_NODE');
  END IF;
  RETURN expected;
END $$;
CREATE OR REPLACE FUNCTION infradesk_onboarding_phase_sequence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected text[]; snapshot jsonb;
BEGIN
  SELECT input_snapshot INTO snapshot FROM remnawave_node_onboarding WHERE id=NEW.run_id;
  expected:=infradesk_onboarding_expected_phases(snapshot);
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
CREATE OR REPLACE FUNCTION infradesk_onboarding_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.input_snapshot IS DISTINCT FROM OLD.input_snapshot OR NEW.organization_id<>OLD.organization_id
    OR NEW.integration_id<>OLD.integration_id OR NEW.resource_id<>OLD.resource_id OR NEW.created_by<>OLD.created_by
    OR NEW.created_at<>OLD.created_at THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_SNAPSHOT_IMMUTABLE'; END IF;
  IF OLD.state IN ('SUCCEEDED','FAILED','UNKNOWN') AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_TERMINAL'; END IF;
  IF OLD.external_node_id IS NOT NULL AND NEW.external_node_id IS DISTINCT FROM OLD.external_node_id THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_IDENTITY_IMMUTABLE'; END IF;
  IF NEW.state='SUCCEEDED' AND (NEW.phase<>'FINAL_VERIFY' OR
    (SELECT count(*) FROM remnawave_node_onboarding_phase WHERE run_id=NEW.id AND state='SUCCEEDED')<>cardinality(infradesk_onboarding_expected_phases(NEW.input_snapshot))) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_VERIFICATION_REQUIRED'; END IF;
  RETURN NEW;
END $$;
