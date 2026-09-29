# External integrations: Stage 24A

Organization owners can open **Integrations**, add a Remnawave panel, save its API token and
optional Caddy API key, test the connection, then enable it. New integrations are disabled.
Enable currently records intent only; no background synchronization or action is started.

The base URL must be an absolute HTTP or HTTPS URL with a host. A path prefix is allowed;
user information, query parameters, fragments and invalid ports are rejected. Trailing slashes
are normalized. The read-only test sends `GET <base URL>/api/system/stats` with
`Authorization: Bearer <API token>` and, when configured, `X-Api-Key: <Caddy API key>`.
It accepts a successful JSON response only when the Remnawave stats envelope has numeric
`response.uptime` and `response.users.totalUsers` fields. The response body is not returned
or logged.

The integration credential is encrypted in `integration_secret` with the deployment's existing
master key and a separate integration AAD kind. Updating without `credentials` preserves it;
supplying `credentials` replaces the entire credential. An absent Caddy key in that replacement
removes the old one. API responses contain only configured flags.

The runtime uses a single validated HTTP client for integrations. DNS is resolved and validated
at socket connection time; the validated address is the dialed address. Loopback, link-local and
multicast addresses are always blocked. Private destinations require the operator setting
`INFRADESK_INTEGRATIONS_ALLOW_PRIVATE_DESTINATIONS=true` (default: false). The separate
`INFRADESK_INTEGRATIONS_REQUEST_TIMEOUT_SECONDS` defaults to 10 seconds and accepts 1–120.
There is no per-integration bypass of this policy.

The test intent and the encrypted credential read commit in a short PostgreSQL transaction.
Decryption and the external API call happen afterward, without holding a database connection.
Only the `INTEGRATION_TEST_REQUESTED` audit action is recorded; the journal does not imply that
the external call succeeded. All integration endpoints require `MANAGE_INTEGRATIONS`, held by
organization owners.

Stage 24B can add discovery and synchronization on this separate provider registry. Stage 24A
does not read Nodes, Hosts, Config Profiles, users, traffic or metrics, and performs no external
actions.
