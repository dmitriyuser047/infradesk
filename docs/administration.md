# Users, organizations and administration

InfraDesk supports multiple independent organizations per account. In **All organizations**,
choose **Create organization**; the creator becomes its owner. Switching organization changes
the URL context and loads only that organization's projects and infrastructure.

Inside an organization, **People and access** lets owners and administrators create users
or add an existing account by email. Adding an existing account leaves its password unchanged.
A user's role and access status apply independently in each organization.

| Organization role | Infrastructure | People and access |
| --- | --- | --- |
| Owner | All existing organization capabilities | Assign/change all organization roles |
| Administrator | All existing organization capabilities | Manage operators and members; cannot assign or change owners or administrators |
| Operator | Read, synchronization, container operations, monitoring rules | No access management |
| Member | Read only | No access management |

Installation administration is a separate capability, never inferred from organization ownership.
The **Administration → Users** section manages accounts, blocking/unblocking, installation
administrators, and memberships in any active organization. Installation administrators still
need an active membership to open normal organization infrastructure APIs.

Blocking an account revokes its authentication sessions. Removing an organization membership
affects that organization only. The last active owner of an active organization and the last
active installation administrator cannot be removed or blocked. Transfer responsibility first.
Changes record both their actor and target; existing audit/history remains available.

## Initial installation administrator

Fresh installations explicitly bootstrap the initial account through the installer. Its bootstrap
email also selects the first installation administrator. No other organization owner is elevated.
When upgrading an existing installation whose bootstrap credentials have already been removed,
set this non-secret variable to an **existing active account** in the protected environment file:

```dotenv
INFRADESK_BOOTSTRAP_ADMINISTRATOR_EMAIL=admin@example.com
```

Recreate the backend after updating the deployment bundle. V66 creates administration tables;
runtime bootstrap appoints the account transactionally with a journal entry. A missing/inactive
account fails initial provisioning. Once an administrator record exists, bootstrap is a no-op,
including after rights are removed; it cannot undo an administrator's decision at restart.
Remove the variable after initial provisioning if desired. Never grant global rights to all
existing owners through a migration or database workaround.

## Implementation and Global principles

The implementation follows `docs/development-and-code-review-standard.md`: explicit typed
operations, bounded credential-free projections, application authorization, persistence ports,
and transactional mutation plus audit. Global's object/selection/operation separation is applied
through the administration projection and use case. Global/BS/RPL module source was not available
in this repository; this is not a claim of direct reuse or a review of those implementations.

V66 adds a short shared access-change lock. Mutations reauthorize their actor after obtaining
the lock, then evaluate last-owner/administrator invariants. This serializes concurrent access
changes. Bcrypt runs outside the database transaction, with authorization checked before hashing
and again before writing. Initial passwords follow account validation and additionally cannot
exceed bcrypt's 72 UTF-8 byte input limit. Neither projections, audit, command formatting nor
browser storage contain the password or its hash.

Organization and user creation use stable `requestId` identities, atomically stored alongside
their result. Repeating the same normalized intent returns the original result; a conflicting
intent receives `ADMINISTRATION_REQUEST_CONFLICT`. The password is deliberately excluded from
the stored fingerprint: retry cannot reset an already created account. Organization retries also
verify current membership. Membership/status changes require the observed `expectedUpdatedAt`;
stale forms cannot overwrite a later change. The browser has no automatic mutation retry and
discards mutation variables after its observer leaves. No optimistic permission grant is used.

## HTTP contracts

All paths below are under `/api/v1`; session authentication is mandatory. Organization routes
also require current membership, and the administration use case enforces the role hierarchy.

| Method / path | Operation |
| --- | --- |
| POST `/organizations` | Create organization and creator's owner membership |
| GET `/organizations/:org/members` | Credential-free members page |
| POST `/organizations/:org/members/users` | Create account with membership in this organization |
| POST `/organizations/:org/members` | Add existing account by email |
| PUT `/organizations/:org/members/:user` | Change role/access using observed version |
| GET / POST `/administration/users` | List/create installation accounts |
| GET / PATCH `/administration/users/:user` | Read/change active status and installation rights |
| GET `/administration/users/:user/memberships` | List independent organization memberships |
| GET `/administration/organizations` | List active organizations |
| GET / PUT `/administration/organizations/:org/members/:user` | Read/change an exact membership |
| GET `/administration/audit` | Bounded administration journal |

Pages return `{items,nextCursor}`. Users/members/events use 50 items; organizations use 100.
User/member pages use three SQL statements including authorization and active-account validation,
for 1, 100 and 500 accounts, not one aggregate/role query per row. Cursor order is UUID for
users/organizations/memberships and `(occurred_at,id)` descending for audit. Exact membership
lookup supports editing users whose membership is outside the currently loaded page.

## Verification

`AdministrationIntegrationSpec` runs the production use case, repository, migration and routes
against real PostgreSQL. It covers password verification, multiple organizations, hierarchy and
tenant boundaries, durable/concurrent retries, stale versions, concurrent last-owner/admin
removals, session revocation, bootstrap stability, and transaction rollback on audit failure.
`AdministrationPages.test.tsx` covers permission gates, pagination, accessible tabs, restricted
role choices, versioned updates, creation/retry identity and protected-account errors.
The existing migration/restore fixtures include removal of V65/V66 additive objects only inside
their disposable old-schema test databases; production migrations remain immutable.

Visual review uses light/dark desktop and 390px mobile pages, keyboard-dismissable focus-trapped
dialogs and overflow checks. The design reference is
[Team management — Untitled UI](https://dribbble.com/shots/18567078-Team-management-Untitled-UI),
adapted to InfraDesk's existing sidebar, typography, spacing and purple accents.
