-- Protocol intent extends the existing immutable onboarding journal. Legacy runs keep their phases.
ALTER TABLE remnawave_node_onboarding ADD COLUMN protocol_binding jsonb;
ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_onboarding_lifecycle_version_check;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_onboarding_lifecycle_version_check CHECK
 (NOT(input_snapshot ? 'lifecycleVersion') OR input_snapshot->'lifecycleVersion' IN
  ('1'::jsonb,'2'::jsonb,'3'::jsonb,'4'::jsonb,'5'::jsonb,'6'::jsonb));
ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_node_onboarding_phase_check;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_node_onboarding_phase_check CHECK(phase IN
 ('VALIDATE','PREPARE_SERVER','DELETE_NODE','CONFIRM_NODE_DELETED','RETIRE_NODE_FIREWALL','RETIRE_LOCAL_NODE','CREATE_NODE',
 'GET_INSTALLATION_DATA','RESOLVE_PANEL_SOURCE','CONFIGURE_NODE_FIREWALL','ADD_PANEL_SOURCES','FINALIZE_PANEL_SOURCES',
 'OBSERVE_PANEL_SOURCE','ADD_OBSERVED_PANEL_SOURCE','VERIFY_OBSERVED_PANEL','UPDATE_NODE_ADDRESS',
 'INSTALL_NODE','START_NODE','VERIFY_LOCAL_NODE','WAIT_FOR_PANEL','SYNC_INVENTORY','BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY',
 'CREATE_PROTOCOL_PROFILE','ISSUE_TLS','INSTALL_TLS','CONFIGURE_CLIENT_FIREWALL','VERIFY_PROTOCOL'));
ALTER TABLE remnawave_node_onboarding_phase DROP CONSTRAINT remnawave_node_onboarding_phase_position_check;
ALTER TABLE remnawave_node_onboarding_phase ADD CONSTRAINT remnawave_node_onboarding_phase_position_check CHECK(position BETWEEN 0 AND 28);

ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_protocol_intent_shape CHECK
 (NOT(input_snapshot ? 'protocol') OR (
  input_snapshot->'lifecycleVersion'='6'::jsonb AND jsonb_typeof(input_snapshot->'protocol')='object' AND
  input_snapshot->'protocol'->'version'='1'::jsonb AND
  input_snapshot->>'configProfileId'='00000000-0000-0000-0000-000000000000' AND
  input_snapshot->'activeInboundIds'='[]'::jsonb AND
  (input_snapshot->'protocol'->>'port')::int BETWEEN 1 AND 65535 AND
  (input_snapshot->'protocol'->>'port')::int<>(input_snapshot->>'nodePort')::int AND
  CASE input_snapshot->'protocol'->>'kind'
   WHEN 'SHADOWSOCKS' THEN
    ((input_snapshot->'protocol')-ARRAY['version','kind','port','method'])='{}'::jsonb AND
    input_snapshot->'protocol'->>'method' IN ('chacha20-ietf-poly1305','aes-128-gcm','aes-256-gcm')
   WHEN 'HYSTERIA2' THEN
    ((input_snapshot->'protocol')-ARRAY['version','kind','port','serverName'])='{}'::jsonb AND
    input_snapshot->'protocol'->>'serverName' ~ '^[a-z0-9][a-z0-9.-]{1,251}[a-z0-9]$'
   ELSE false END
 ) IS TRUE);
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_protocol_binding_shape CHECK
 (protocol_binding IS NULL OR (
  input_snapshot ? 'protocol' AND jsonb_typeof(protocol_binding)='object' AND
  protocol_binding ?& ARRAY['profileId','inboundIds','configSha256'] AND
  (protocol_binding-ARRAY['profileId','inboundIds','configSha256'])='{}'::jsonb AND
  protocol_binding->>'profileId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' AND
  protocol_binding->>'profileId'<>'00000000-0000-0000-0000-000000000000' AND
  jsonb_typeof(protocol_binding->'inboundIds')='array' AND
  jsonb_array_length(protocol_binding->'inboundIds')=1 AND
  protocol_binding->'inboundIds'->>0 ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' AND
  protocol_binding->>'configSha256' ~ '^[0-9a-f]{64}$'
 ) IS TRUE);

CREATE FUNCTION infradesk_onboarding_protocol_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' AND NEW.protocol_binding IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM remnawave_node_onboarding source WHERE source.organization_id=NEW.organization_id
    AND source.integration_id=NEW.integration_id AND source.resource_id=NEW.resource_id
    AND source.id::text=NEW.input_snapshot->'recovery'->>'sourceRunId'
    AND source.state IN ('SUCCEEDED','FAILED','UNKNOWN')
    AND source.protocol_binding=NEW.protocol_binding
    AND source.input_snapshot->'protocol'=NEW.input_snapshot->'protocol') THEN
  RAISE EXCEPTION 'REMNAWAVE_PROTOCOL_BINDING_SOURCE_INVALID';
 END IF;
 IF TG_OP='UPDATE' THEN
  IF OLD.protocol_binding IS NOT NULL AND NEW.protocol_binding IS DISTINCT FROM OLD.protocol_binding THEN
   RAISE EXCEPTION 'REMNAWAVE_PROTOCOL_BINDING_IMMUTABLE';
  END IF;
  IF OLD.protocol_binding IS NULL AND NEW.protocol_binding IS NOT NULL AND
    (OLD.phase<>'CREATE_PROTOCOL_PROFILE' OR NEW.phase<>'CREATE_NODE') THEN
   RAISE EXCEPTION 'REMNAWAVE_PROTOCOL_BINDING_PHASE_INVALID';
  END IF;
 END IF;
 IF NEW.external_node_id IS NOT NULL AND NEW.input_snapshot ? 'protocol' AND NEW.protocol_binding IS NULL THEN
  RAISE EXCEPTION 'REMNAWAVE_PROTOCOL_BINDING_REQUIRED';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER remnawave_protocol_binding_guard BEFORE INSERT OR UPDATE ON remnawave_node_onboarding
 FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_protocol_binding_guard();

ALTER FUNCTION infradesk_onboarding_expected_phases(jsonb) RENAME TO infradesk_onboarding_v5_expected_phases;
CREATE FUNCTION infradesk_onboarding_expected_phases(snapshot jsonb) RETURNS text[] LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE expected text[];
BEGIN
 expected:=infradesk_onboarding_v5_expected_phases(snapshot);
 IF NOT(snapshot ? 'protocol') THEN RETURN expected; END IF;
 SELECT array_agg(p ORDER BY position) INTO expected FROM (
  SELECT p,ordinality::numeric position FROM unnest(expected) WITH ORDINALITY AS phases(p,ordinality)
  UNION ALL SELECT 'CREATE_PROTOCOL_PROFILE',array_position(expected,'CREATE_NODE')::numeric-0.1
   WHERE 'CREATE_NODE'=ANY(expected)
  UNION ALL SELECT 'ISSUE_TLS',array_position(expected,'CREATE_NODE')::numeric-0.2
   WHERE 'CREATE_NODE'=ANY(expected) AND snapshot ? 'tlsHttp01'
  UNION ALL SELECT 'INSTALL_TLS',array_position(expected,'INSTALL_NODE')::numeric-0.2
   WHERE 'INSTALL_NODE'=ANY(expected) AND (snapshot ? 'tlsCertificateId' OR snapshot ? 'tlsHttp01')
  UNION ALL SELECT 'CONFIGURE_CLIENT_FIREWALL',array_position(expected,'INSTALL_NODE')::numeric-0.1
   WHERE 'INSTALL_NODE'=ANY(expected)
  UNION ALL SELECT 'VERIFY_PROTOCOL',array_position(expected,'FINAL_VERIFY')::numeric-0.1
 ) phases;
 RETURN expected;
END $$;
