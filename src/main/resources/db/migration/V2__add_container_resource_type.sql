INSERT INTO resource_type (
    id,
    code,
    name,
    schema_version,
    capabilities
)
VALUES (
           '10000000-0000-0000-0000-000000000002',
           'CONTAINER',
           'Container',
           1,
           ARRAY[]::text[]
       );