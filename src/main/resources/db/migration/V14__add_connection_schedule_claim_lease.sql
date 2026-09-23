ALTER TABLE connection_schedule
  ADD COLUMN claimed_by uuid,
  ADD COLUMN claimed_until timestamptz,
  ADD CONSTRAINT ck_connection_schedule_claim_pair
    CHECK (
      (claimed_by IS NULL AND claimed_until IS NULL)
      OR (claimed_by IS NOT NULL AND claimed_until IS NOT NULL)
    );
