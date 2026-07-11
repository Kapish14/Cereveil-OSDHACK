Status: ready-for-agent

# Enforce Guardian Device revocation and two-device limit

## Parent

.scratch/guardian-auth-bootstrap-backend/PRD.md

## What to build

Make Guardian auth bootstrap enforce Guardian Device access rules. A revoked Guardian Device installation must stay revoked, and a Guardian Account must not exceed two active Guardian Devices. Bootstrap should report safe typed errors without silently reactivating or auto-revoking devices.

## Acceptance criteria

- [ ] Bootstrap fails with `DEVICE_REVOKED` when the same Guardian installation has a revoked Guardian Device row.
- [ ] A revoked Guardian Device is not reactivated by signing in again.
- [ ] Bootstrap creates a new Guardian Device only when the Guardian Account has fewer than two active Guardian Devices.
- [ ] Bootstrap fails with `DEVICE_LIMIT_REACHED` when a new third active Guardian Device would be created.
- [ ] Existing active Guardian Devices are not auto-revoked during bootstrap.
- [ ] Existing active Guardian installations can still bootstrap successfully when the account already has two active Guardian Devices.
- [ ] Tests cover revoked-device rejection, third-device rejection, no auto-revocation, and existing-device success at the two-device limit.

## Blocked by

- .scratch/guardian-auth-bootstrap-backend/issues/02-make-bootstrap-idempotent-and-metadata-safe.md
