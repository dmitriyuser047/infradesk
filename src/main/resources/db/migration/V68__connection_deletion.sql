-- A deleted connection is a tombstone, not a removed row: syncs, deployments, provisioning runs,
-- terminal sessions and the journal still name it. It is always inactive, so every worker that
-- already refuses an inactive connection refuses a deleted one too.
ALTER TABLE connection ADD COLUMN deleted_at timestamptz;
ALTER TABLE connection ADD CONSTRAINT ck_connection_deleted_inactive
  CHECK (deleted_at IS NULL OR NOT is_active);

-- The code of a deleted connection becomes free for a new one.
DROP INDEX ux_connection_org_code_ci;
CREATE UNIQUE INDEX ux_connection_org_code_ci ON connection (organization_id, lower(code))
  WHERE deleted_at IS NULL;
