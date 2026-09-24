-- An execution has to carry the moment after which its own attempt may be declared abandoned.
-- Deriving it later from the connection would read settings that may have changed since the
-- command was sent, and could retire a RUNNING execution whose command is still in flight.

ALTER TABLE operation_execution ADD COLUMN recover_after_at timestamptz;

-- Existing rows were created under the former fixed ten-minute horizon.
UPDATE operation_execution SET recover_after_at = started_at + interval '10 minutes'
  WHERE recover_after_at IS NULL;

ALTER TABLE operation_execution ALTER COLUMN recover_after_at SET NOT NULL;

ALTER TABLE operation_execution ADD CONSTRAINT ck_operation_execution_recover_after_at
  CHECK (recover_after_at > started_at);
