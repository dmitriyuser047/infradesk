-- V7 changes only new plans; all persisted V1-V6 phase sequences remain immutable.
ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_onboarding_lifecycle_version_check;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_onboarding_lifecycle_version_check CHECK
 (NOT(input_snapshot ? 'lifecycleVersion') OR input_snapshot->'lifecycleVersion' IN
 ('1'::jsonb,'2'::jsonb,'3'::jsonb,'4'::jsonb,'5'::jsonb,'6'::jsonb,'7'::jsonb));
ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_protocol_intent_shape;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_protocol_intent_shape CHECK
 (NOT(input_snapshot ? 'protocol') OR (
  input_snapshot->'lifecycleVersion' IN ('6'::jsonb,'7'::jsonb) AND jsonb_typeof(input_snapshot->'protocol')='object' AND
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

ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_node_onboarding_phase_check;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_node_onboarding_phase_check CHECK(phase IN
 ('VALIDATE','PREPARE_SERVER','PROTOCOL_PREFLIGHT','DELETE_NODE','CONFIRM_NODE_DELETED','RETIRE_NODE_FIREWALL','RETIRE_LOCAL_NODE','CREATE_NODE',
 'GET_INSTALLATION_DATA','RESOLVE_PANEL_SOURCE','CONFIGURE_NODE_FIREWALL','ADD_PANEL_SOURCES','FINALIZE_PANEL_SOURCES',
 'OBSERVE_PANEL_SOURCE','ADD_OBSERVED_PANEL_SOURCE','VERIFY_OBSERVED_PANEL','UPDATE_NODE_ADDRESS',
 'INSTALL_NODE','START_NODE','VERIFY_LOCAL_NODE','WAIT_FOR_PANEL','SYNC_INVENTORY','BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY',
 'CREATE_PROTOCOL_PROFILE','ISSUE_TLS','INSTALL_TLS','CONFIGURE_CLIENT_FIREWALL','VERIFY_PROTOCOL'));
ALTER TABLE remnawave_node_onboarding_phase DROP CONSTRAINT remnawave_node_onboarding_phase_position_check;
ALTER TABLE remnawave_node_onboarding_phase ADD CONSTRAINT remnawave_node_onboarding_phase_position_check CHECK(position BETWEEN 0 AND 29);
ALTER FUNCTION infradesk_onboarding_expected_phases(jsonb) RENAME TO infradesk_onboarding_v6_expected_phases;
CREATE FUNCTION infradesk_onboarding_expected_phases(snapshot jsonb) RETURNS text[] LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE expected text[];
BEGIN
 expected:=infradesk_onboarding_v6_expected_phases(snapshot);
 IF snapshot->'lifecycleVersion' IS DISTINCT FROM '7'::jsonb OR NOT(snapshot ? 'protocol') THEN RETURN expected; END IF;
 SELECT array_agg(p ORDER BY position) INTO expected FROM (
  SELECT p,ordinality::numeric position FROM unnest(expected) WITH ORDINALITY AS phases(p,ordinality)
   WHERE p<>'VERIFY_PROTOCOL'
  UNION ALL SELECT 'PROTOCOL_PREFLIGHT',array_position(expected,'PREPARE_SERVER')::numeric+0.1
  UNION ALL SELECT 'VERIFY_PROTOCOL',array_position(expected,'SYNC_INVENTORY')::numeric-0.1
 ) phases;
 RETURN expected;
END $$;

-- Serialize identity reservations, issuance, and cleanup across transactions.
-- UUID collisions in the advisory hash merely serialize unrelated work.
CREATE FUNCTION infradesk_tls_identity_lock(identity_id uuid) RETURNS void LANGUAGE sql VOLATILE AS $$
 SELECT pg_advisory_xact_lock(hashtextextended('infradesk:node-tls:' || identity_id::text,0));
$$;

CREATE OR REPLACE FUNCTION infradesk_onboarding_tls_issuance_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE requested uuid; requested_domain text;
BEGIN
 IF NEW.input_snapshot ? 'tlsHttp01' THEN
  requested:=(NEW.input_snapshot->'tlsHttp01'->>'certificateId')::uuid;
  PERFORM infradesk_tls_identity_lock(requested);
  requested_domain:=NEW.input_snapshot->'protocol'->>'serverName';
  IF EXISTS(SELECT 1 FROM remnawave_node_tls_certificate c WHERE c.id=requested AND
   (c.organization_id<>NEW.organization_id OR c.resource_id<>NEW.resource_id OR c.domain<>requested_domain)) THEN
   RAISE EXCEPTION 'REMNAWAVE_TLS_REFERENCE_INVALID';
  END IF;
  INSERT INTO remnawave_tls_issuance_identity(id,organization_id,resource_id,domain)
   VALUES(requested,NEW.organization_id,NEW.resource_id,requested_domain) ON CONFLICT(id) DO NOTHING;
  IF NOT EXISTS(SELECT 1 FROM remnawave_tls_issuance_identity r WHERE r.id=requested
   AND r.organization_id=NEW.organization_id AND r.resource_id=NEW.resource_id AND r.domain=requested_domain) THEN
   RAISE EXCEPTION 'REMNAWAVE_TLS_REFERENCE_INVALID';
  END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION infradesk_tls_certificate_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM infradesk_tls_identity_lock(NEW.id);
 IF EXISTS(SELECT 1 FROM remnawave_tls_issuance_identity r WHERE r.id=NEW.id AND
  (r.organization_id<>NEW.organization_id OR r.resource_id<>NEW.resource_id OR r.domain<>NEW.domain)) THEN
  RAISE EXCEPTION 'REMNAWAVE_TLS_REFERENCE_INVALID';
 END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION infradesk_node_tls_referenced_delete_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM infradesk_tls_identity_lock(OLD.id);
 IF TG_TABLE_NAME='remnawave_tls_issuance_identity' AND EXISTS(
  SELECT 1 FROM remnawave_node_tls_certificate c WHERE c.id=OLD.id) THEN
  RAISE EXCEPTION 'REMNAWAVE_TLS_VERSION_REFERENCED';
 END IF;
 IF EXISTS(SELECT 1 FROM remnawave_node_onboarding r WHERE (r.input_snapshot->>'tlsCertificateId'=OLD.id::text OR r.input_snapshot->'tlsHttp01'->>'certificateId'=OLD.id::text)) THEN
  RAISE EXCEPTION 'REMNAWAVE_TLS_VERSION_REFERENCED';
 END IF;
 RETURN OLD;
END $$;

CREATE OR REPLACE FUNCTION infradesk_onboarding_tls_reference_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.input_snapshot ? 'tlsCertificateId' THEN
  PERFORM infradesk_tls_identity_lock((NEW.input_snapshot->>'tlsCertificateId')::uuid);
 END IF;
 IF NEW.input_snapshot ? 'tlsCertificateId' AND NOT EXISTS(
   SELECT 1 FROM remnawave_node_tls_certificate c WHERE c.organization_id=NEW.organization_id
    AND c.resource_id=NEW.resource_id AND c.id::text=NEW.input_snapshot->>'tlsCertificateId'
    AND NEW.input_snapshot->'protocol'->>'kind'='HYSTERIA2'
    AND c.domain=NEW.input_snapshot->'protocol'->>'serverName'
    AND c.expires_at>clock_timestamp()+interval '7 days') THEN
  RAISE EXCEPTION 'REMNAWAVE_TLS_REFERENCE_INVALID';
 END IF;
 RETURN NEW;
END $$;
