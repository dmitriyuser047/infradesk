-- An executable AUTO policy authorizes one bounded packet candidate, journalled before its allow.
ALTER TABLE remnawave_node_onboarding ADD COLUMN observed_panel_source jsonb;
ALTER TABLE remnawave_node_onboarding ADD COLUMN connectivity_completion varchar(16)
 CHECK(connectivity_completion IN ('PROMOTED','ROLLED_BACK'));
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_observed_source_shape CHECK
 (observed_panel_source IS NULL OR (
  (input_snapshot->>'lifecycleVersion')::int>=5 AND input_snapshot->>'panelSourceMode'='AUTO' AND
  jsonb_typeof(observed_panel_source)='object' AND
  observed_panel_source ?& ARRAY['status','sources'] AND
  (observed_panel_source-ARRAY['status','sources'])='{}'::jsonb AND
  jsonb_typeof(observed_panel_source->'sources')='array' AND
  CASE WHEN observed_panel_source->>'status'='AUTO_OBSERVED' THEN
    jsonb_array_length(observed_panel_source->'sources')=1 AND
    jsonb_typeof(observed_panel_source->'sources'->0)='string' AND
    (observed_panel_source->'sources'->>0)~'^[0-9a-fA-F:.]+/(32|128)$'
  ELSE observed_panel_source->>'status' IN ('NOT_REQUIRED','NO_TRAFFIC','AMBIGUOUS','UNAVAILABLE') AND
    jsonb_array_length(observed_panel_source->'sources')=0 END) IS TRUE);
ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_connectivity_finding_object;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_connectivity_finding_object CHECK
 (connectivity_finding IS NULL OR (jsonb_typeof(connectivity_finding)='object' AND
  connectivity_finding ?& ARRAY['panelSources','sourceEvidence','connected'] AND
  (connectivity_finding-ARRAY['panelSources','sourceEvidence','connected'])='{}'::jsonb AND
  jsonb_typeof(connectivity_finding->'panelSources')='array' AND
  jsonb_array_length(connectivity_finding->'panelSources') BETWEEN 1 AND 32 AND
  connectivity_finding->>'sourceEvidence' IN ('AUTO_CANDIDATE','AUTO_OBSERVED','MANUAL','LEGACY_MANUAL') AND
  jsonb_typeof(connectivity_finding->'connected')='boolean') IS TRUE);

CREATE FUNCTION infradesk_observed_panel_source_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.observed_panel_source IS NULL AND NEW.observed_panel_source IS NOT NULL AND
   (OLD.phase<>'OBSERVE_PANEL_SOURCE' OR NEW.phase<>'ADD_OBSERVED_PANEL_SOURCE') THEN
   RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_OBSERVED_SOURCE_PHASE_INVALID';
 END IF;
 IF OLD.connectivity_completion IS NULL AND NEW.connectivity_completion IS NOT NULL AND OLD.phase<>'FINALIZE_PANEL_SOURCES' THEN
   RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_CONNECTIVITY_COMPLETION_PHASE_INVALID';
 END IF;
 IF OLD.observed_panel_source IS NOT NULL AND NEW.observed_panel_source IS DISTINCT FROM OLD.observed_panel_source
   OR OLD.connectivity_completion IS NOT NULL AND NEW.connectivity_completion IS DISTINCT FROM OLD.connectivity_completion THEN
   RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_OBSERVED_SOURCE_IMMUTABLE';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER remnawave_observed_panel_source_immutable BEFORE UPDATE ON remnawave_node_onboarding
 FOR EACH ROW EXECUTE FUNCTION infradesk_observed_panel_source_immutable();

ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_observed_finding_consistent CHECK
 (connectivity_finding->>'sourceEvidence' IS DISTINCT FROM 'AUTO_OBSERVED' OR
  (observed_panel_source->>'status'='AUTO_OBSERVED' AND
   observed_panel_source->'sources'=connectivity_finding->'panelSources') IS TRUE);
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_promoted_connection_confirmed CHECK
 (connectivity_completion IS DISTINCT FROM 'PROMOTED' OR (connectivity_finding->'connected'='true'::jsonb) IS TRUE);
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_node_address_evidence_shape CHECK
 ((NOT(input_snapshot ? 'nodeAddress') AND NOT(input_snapshot ? 'nodeAddressMode')) OR (
  jsonb_typeof(input_snapshot->'nodeAddress')='object' AND
  input_snapshot->'nodeAddress' ?& ARRAY['mode','address','publicAddresses'] AND
  ((input_snapshot->'nodeAddress')-ARRAY['mode','address','publicAddresses'])='{}'::jsonb AND
  input_snapshot->'nodeAddress'->>'mode'=input_snapshot->>'nodeAddressMode' AND
  input_snapshot->>'nodeAddressMode' IN ('PUBLIC_IP','DOMAIN') AND
  input_snapshot->'nodeAddress'->>'address'=input_snapshot->>'address' AND
  jsonb_typeof(input_snapshot->'nodeAddress'->'publicAddresses')='array' AND
  jsonb_array_length(input_snapshot->'nodeAddress'->'publicAddresses') BETWEEN 1 AND 32 AND
  (input_snapshot->>'nodeAddressMode'='DOMAIN' OR input_snapshot->'nodeAddress'->'publicAddresses' ? (input_snapshot->>'address'))
 ) IS TRUE);

ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_onboarding_lifecycle_version_check;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_onboarding_lifecycle_version_check CHECK
 (NOT(input_snapshot ? 'lifecycleVersion') OR input_snapshot->'lifecycleVersion' IN ('1'::jsonb,'2'::jsonb,'3'::jsonb,'4'::jsonb,'5'::jsonb));
ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_node_onboarding_phase_check;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_node_onboarding_phase_check CHECK(phase IN
 ('VALIDATE','PREPARE_SERVER','DELETE_NODE','CONFIRM_NODE_DELETED','RETIRE_NODE_FIREWALL','RETIRE_LOCAL_NODE','CREATE_NODE',
 'GET_INSTALLATION_DATA','RESOLVE_PANEL_SOURCE','CONFIGURE_NODE_FIREWALL','ADD_PANEL_SOURCES','FINALIZE_PANEL_SOURCES',
 'OBSERVE_PANEL_SOURCE','ADD_OBSERVED_PANEL_SOURCE','VERIFY_OBSERVED_PANEL','UPDATE_NODE_ADDRESS',
 'INSTALL_NODE','START_NODE','VERIFY_LOCAL_NODE','WAIT_FOR_PANEL','SYNC_INVENTORY','BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY'));
ALTER TABLE remnawave_node_onboarding_phase DROP CONSTRAINT remnawave_node_onboarding_phase_position_check;
ALTER TABLE remnawave_node_onboarding_phase ADD CONSTRAINT remnawave_node_onboarding_phase_position_check CHECK(position BETWEEN 0 AND 23);

ALTER FUNCTION infradesk_onboarding_expected_phases(jsonb) RENAME TO infradesk_onboarding_v4_expected_phases;
CREATE FUNCTION infradesk_onboarding_expected_phases(snapshot jsonb) RETURNS text[] LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE expected text[];
BEGIN
 IF coalesce((snapshot->>'lifecycleVersion')::int,1)<5 THEN RETURN infradesk_onboarding_v4_expected_phases(snapshot); END IF;
 expected:=infradesk_onboarding_v4_expected_phases(jsonb_set(snapshot,'{lifecycleVersion}','4'::jsonb));
 IF snapshot->>'panelSourceMode'='AUTO' THEN
  SELECT array_agg(p ORDER BY position) INTO expected FROM (
   SELECT p,ordinality::numeric position FROM unnest(expected) WITH ORDINALITY AS phases(p,ordinality)
   UNION ALL SELECT 'OBSERVE_PANEL_SOURCE',array_position(expected,'FINALIZE_PANEL_SOURCES')::numeric-0.3
   UNION ALL SELECT 'ADD_OBSERVED_PANEL_SOURCE',array_position(expected,'FINALIZE_PANEL_SOURCES')::numeric-0.2
   UNION ALL SELECT 'VERIFY_OBSERVED_PANEL',array_position(expected,'FINALIZE_PANEL_SOURCES')::numeric-0.1
  ) phases;
 END IF;
 IF snapshot->'recovery'->>'action' IN ('RECOVER','REPAIR_PANEL_CONNECTIVITY') AND snapshot->'recovery' ? 'previousNodeAddress' THEN
  SELECT array_agg(p ORDER BY position) INTO expected FROM (
   SELECT p,ordinality::numeric position FROM unnest(expected) WITH ORDINALITY AS phases(p,ordinality)
   UNION ALL SELECT 'UPDATE_NODE_ADDRESS',array_position(expected,'RESOLVE_PANEL_SOURCE')::numeric-0.5
  ) phases;
 END IF;
 RETURN expected;
END $$;
