CREATE TABLE connection_secret (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  kind varchar(32) NOT NULL CHECK (kind = 'SSH_PASSWORD'),
  nonce bytea NOT NULL CHECK (octet_length(nonce) = 12),
  ciphertext bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_connection_secret_id_org UNIQUE (id, organization_id)
);

CREATE INDEX ix_connection_secret_organization ON connection_secret (organization_id);
