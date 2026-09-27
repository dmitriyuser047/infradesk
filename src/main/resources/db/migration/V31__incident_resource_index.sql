-- The incidents of one resource: the resource incident tab pages through them newest first, and
-- the resource context and the connection read models count or filter them per resource. Without
-- it each of those reads scans every incident of the organization.
CREATE INDEX ix_incident_resource_opened
  ON incident (
    organization_id,
    resource_id,
    opened_at DESC,
    id DESC
  );
