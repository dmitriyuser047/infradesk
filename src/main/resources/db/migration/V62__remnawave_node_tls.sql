CREATE TABLE remnawave_node_tls_certificate (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL REFERENCES organization(id),
 resource_id uuid NOT NULL,
 domain varchar(253) NOT NULL,
 fingerprint varchar(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 expires_at timestamptz NOT NULL,
 kind varchar(40) NOT NULL CHECK(kind='REMNAWAVE_NODE_TLS'),
 nonce bytea NOT NULL CHECK(octet_length(nonce)=12),
 ciphertext bytea NOT NULL CHECK(octet_length(ciphertext) BETWEEN 16 AND 65536),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 UNIQUE(organization_id,resource_id,id),
 FOREIGN KEY(organization_id,resource_id) REFERENCES resource(organization_id,id)
);
CREATE TABLE remnawave_tls_issuance_identity (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL REFERENCES organization(id),
 resource_id uuid NOT NULL,
 domain varchar(253) NOT NULL,
 FOREIGN KEY(organization_id,resource_id) REFERENCES resource(organization_id,id)
);
CREATE FUNCTION infradesk_onboarding_tls_issuance_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE requested uuid; requested_domain text;
BEGIN
 IF NEW.input_snapshot ? 'tlsHttp01' THEN
  requested:=(NEW.input_snapshot->'tlsHttp01'->>'certificateId')::uuid;
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
CREATE TRIGGER remnawave_onboarding_tls_issuance_identity_guard BEFORE INSERT ON remnawave_node_onboarding
 FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_tls_issuance_identity_guard();
CREATE FUNCTION infradesk_tls_certificate_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM remnawave_tls_issuance_identity r WHERE r.id=NEW.id AND
  (r.organization_id<>NEW.organization_id OR r.resource_id<>NEW.resource_id OR r.domain<>NEW.domain)) THEN
  RAISE EXCEPTION 'REMNAWAVE_TLS_REFERENCE_INVALID';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER remnawave_tls_certificate_identity_guard BEFORE INSERT ON remnawave_node_tls_certificate
 FOR EACH ROW EXECUTE FUNCTION infradesk_tls_certificate_identity_guard();
DO $$
DECLARE previous_check text;
BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous_check FROM pg_constraint
 WHERE conrelid='audit_event'::regclass AND conname='ck_audit_event_action';
 IF previous_check IS NULL THEN RAISE EXCEPTION 'AUDIT_ACTION_CONSTRAINT_MISSING'; END IF;
 ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
 EXECUTE 'ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (' || substring(previous_check FROM 7) ||
  ' OR action=''REMNAWAVE_NODE_CERTIFICATE_IMPORTED'')';
END $$;
CREATE FUNCTION infradesk_node_tls_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'REMNAWAVE_TLS_VERSION_IMMUTABLE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER remnawave_node_tls_immutable BEFORE UPDATE ON remnawave_node_tls_certificate
 FOR EACH ROW EXECUTE FUNCTION infradesk_node_tls_immutable();
CREATE TRIGGER remnawave_tls_issuance_identity_immutable BEFORE UPDATE ON remnawave_tls_issuance_identity
 FOR EACH ROW EXECUTE FUNCTION infradesk_node_tls_immutable();

CREATE FUNCTION infradesk_onboarding_tls_reference_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
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
CREATE TRIGGER remnawave_onboarding_tls_reference_guard BEFORE INSERT ON remnawave_node_onboarding
 FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_tls_reference_guard();

ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_http01_intent_shape CHECK
 (NOT(input_snapshot ? 'tlsHttp01') OR (
  input_snapshot->'protocol'->>'kind'='HYSTERIA2' AND NOT(input_snapshot ? 'tlsCertificateId') AND
  input_snapshot->>'nodePort'<>'80' AND jsonb_typeof(input_snapshot->'tlsHttp01')='object' AND
  (input_snapshot->'tlsHttp01') ?& ARRAY['certificateId','email','agreeTerms'] AND
  ((input_snapshot->'tlsHttp01')-ARRAY['certificateId','email','agreeTerms'])='{}'::jsonb AND
  input_snapshot->'tlsHttp01'->'agreeTerms'='true'::jsonb AND
  input_snapshot->'tlsHttp01'->>'certificateId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' AND
  input_snapshot->'tlsHttp01'->>'certificateId'<>'00000000-0000-0000-0000-000000000000' AND
  length(input_snapshot->'tlsHttp01'->>'email') BETWEEN 3 AND 254
 ) IS TRUE);
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_hysteria_tls_required CHECK
 (input_snapshot->'protocol'->>'kind' IS DISTINCT FROM 'HYSTERIA2' OR
  input_snapshot ? 'tlsCertificateId' OR input_snapshot ? 'tlsHttp01');

CREATE FUNCTION infradesk_node_tls_referenced_delete_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM remnawave_node_onboarding r WHERE r.organization_id=OLD.organization_id
  AND (r.input_snapshot->>'tlsCertificateId'=OLD.id::text OR r.input_snapshot->'tlsHttp01'->>'certificateId'=OLD.id::text)) THEN
  RAISE EXCEPTION 'REMNAWAVE_TLS_VERSION_REFERENCED';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER remnawave_node_tls_referenced_delete_guard BEFORE DELETE ON remnawave_node_tls_certificate
 FOR EACH ROW EXECUTE FUNCTION infradesk_node_tls_referenced_delete_guard();
CREATE TRIGGER remnawave_tls_issuance_identity_referenced_delete_guard BEFORE DELETE ON remnawave_tls_issuance_identity
 FOR EACH ROW EXECUTE FUNCTION infradesk_node_tls_referenced_delete_guard();
