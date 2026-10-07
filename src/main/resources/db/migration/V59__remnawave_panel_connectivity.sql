-- New reviewed network policy; terminal v1/v2/v3 snapshots and sequences remain unchanged.
ALTER TABLE remnawave_node_onboarding ADD COLUMN connectivity_finding jsonb;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_connectivity_finding_object
 CHECK(connectivity_finding IS NULL OR (jsonb_typeof(connectivity_finding)='object' AND
   connectivity_finding ?& ARRAY['panelSources','sourceEvidence','connected'] AND
   (connectivity_finding - ARRAY['panelSources','sourceEvidence','connected'])='{}'::jsonb AND
   jsonb_typeof(connectivity_finding->'panelSources')='array' AND
   jsonb_array_length(connectivity_finding->'panelSources') BETWEEN 1 AND 32 AND
   connectivity_finding->>'sourceEvidence' IN ('AUTO_CANDIDATE','MANUAL','LEGACY_MANUAL') AND
   jsonb_typeof(connectivity_finding->'connected')='boolean') IS TRUE);
ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_onboarding_lifecycle_version_check;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_onboarding_lifecycle_version_check
 CHECK (NOT (input_snapshot ? 'lifecycleVersion') OR input_snapshot->'lifecycleVersion' IN ('1'::jsonb,'2'::jsonb,'3'::jsonb,'4'::jsonb));
ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_node_onboarding_phase_check;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_node_onboarding_phase_check CHECK(phase IN
 ('VALIDATE','PREPARE_SERVER','DELETE_NODE','CONFIRM_NODE_DELETED','RETIRE_NODE_FIREWALL','RETIRE_LOCAL_NODE','CREATE_NODE',
 'GET_INSTALLATION_DATA','RESOLVE_PANEL_SOURCE','CONFIGURE_NODE_FIREWALL','ADD_PANEL_SOURCES','FINALIZE_PANEL_SOURCES',
 'INSTALL_NODE','START_NODE','VERIFY_LOCAL_NODE','WAIT_FOR_PANEL','SYNC_INVENTORY','BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY'));
ALTER TABLE remnawave_node_onboarding_phase DROP CONSTRAINT remnawave_node_onboarding_phase_position_check;
ALTER TABLE remnawave_node_onboarding_phase ADD CONSTRAINT remnawave_node_onboarding_phase_position_check CHECK(position BETWEEN 0 AND 19);

ALTER FUNCTION infradesk_onboarding_expected_phases(jsonb) RENAME TO infradesk_onboarding_v3_expected_phases;
CREATE FUNCTION infradesk_onboarding_expected_phases(snapshot jsonb) RETURNS text[] LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE expected text[];
BEGIN
  IF coalesce((snapshot->>'lifecycleVersion')::int,1)<4 THEN
    RETURN infradesk_onboarding_v3_expected_phases(snapshot);
  END IF;
  IF snapshot->'recovery'->>'action'='REPAIR_PANEL_CONNECTIVITY' THEN
    RETURN ARRAY['VALIDATE','PREPARE_SERVER','RESOLVE_PANEL_SOURCE','ADD_PANEL_SOURCES','WAIT_FOR_PANEL',
      'FINALIZE_PANEL_SOURCES','SYNC_INVENTORY','BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY'];
  END IF;
  expected:=infradesk_onboarding_v3_expected_phases(jsonb_set(snapshot,'{lifecycleVersion}','3'::jsonb));
  SELECT array_agg(p ORDER BY position) INTO expected FROM (
    SELECT p,ordinality::numeric AS position FROM unnest(expected) WITH ORDINALITY AS phases(p,ordinality)
    UNION ALL SELECT 'RESOLVE_PANEL_SOURCE',array_position(expected,'CREATE_NODE')::numeric-0.5
    UNION ALL SELECT 'FINALIZE_PANEL_SOURCES',array_position(expected,'WAIT_FOR_PANEL')::numeric+0.5
  ) ordered_phases;
  RETURN expected;
END $$;

ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_v4_panel_source_required CHECK
 (coalesce((input_snapshot->>'lifecycleVersion')::int,1)<4 OR
   (input_snapshot->'panelSource'->>'confidence' IN ('AUTO_CANDIDATE','MANUAL') AND
    input_snapshot->'panelSource'->'sources'=input_snapshot->'panelCidrs' AND
    jsonb_array_length(input_snapshot->'panelCidrs') BETWEEN 1 AND 32 AND
    input_snapshot->'panelSource'->>'mode'=input_snapshot->>'panelSourceMode') IS TRUE);
