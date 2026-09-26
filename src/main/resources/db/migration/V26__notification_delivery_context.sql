-- The human-facing snapshot of an incident event, taken when the delivery row is written.
--
-- A Telegram message, an email and a webhook's enrichment all render from this one value, so the
-- dispatcher never reads a resource, a rule or an organization to build a message, and the text a
-- person sees is stable across retries even if a resource is renamed in between. It is nullable:
-- rows written before this column existed have none, and the synthetic test-channel event has no
-- incident to describe, so both fall back to the identifier-only message.
ALTER TABLE notification_delivery ADD COLUMN context jsonb;
