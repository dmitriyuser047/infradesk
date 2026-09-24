-- A synchronization has to carry the moment after which its own attempt may be declared
-- abandoned. The former fixed fifteen minutes were unrelated to the timeouts the connection is
-- configured with, so a legitimately long SSH inventory could be retired while it was still
-- running, and a second one started next to it.

ALTER TABLE sync_session ADD COLUMN recover_after_at timestamptz;

-- Existing rows were created under the former fixed horizon.
UPDATE sync_session SET recover_after_at = started_at + interval '900 seconds'
  WHERE recover_after_at IS NULL;

ALTER TABLE sync_session ALTER COLUMN recover_after_at SET NOT NULL;

ALTER TABLE sync_session ADD CONSTRAINT ck_sync_session_recover_after_at
  CHECK (recover_after_at > started_at);
