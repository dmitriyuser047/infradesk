-- Preview plans have no SSH side effects and are retained only long enough for explicit review.
-- The cleanup worker deletes only PLANNED rows; approvals and execution history are immutable.
CREATE INDEX ix_provisioning_expired_plans
  ON provisioning_run(created_at, id)
  WHERE status = 'PLANNED';
