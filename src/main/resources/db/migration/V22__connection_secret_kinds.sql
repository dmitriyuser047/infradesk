-- SSH connections can authenticate with a password or with a private key, so the encrypted
-- payload is now a typed credential rather than a bare password. Existing rows keep their kind
-- and are still read as passwords; nothing is rewritten.

ALTER TABLE connection_secret DROP CONSTRAINT connection_secret_kind_check;

ALTER TABLE connection_secret ADD CONSTRAINT ck_connection_secret_kind
  CHECK (kind IN ('SSH_PASSWORD', 'SSH_CREDENTIAL'));
