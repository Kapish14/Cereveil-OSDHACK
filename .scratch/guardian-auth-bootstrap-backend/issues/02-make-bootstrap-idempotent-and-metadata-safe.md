Status: ready-for-agent

# Make bootstrap idempotent and metadata-safe

## Parent

.scratch/guardian-auth-bootstrap-backend/PRD.md

## What to build

Extend Guardian auth bootstrap so repeated calls for the same Guardian Account and Guardian installation are safe. Bootstrap should reuse existing records, refresh Guardian Device operational timestamps, handle optional display metadata, and update Clerk-derived profile metadata without treating it as identity.

## Acceptance criteria

- [ ] Repeating bootstrap with the same Guardian installation returns the existing Guardian Account, Household, and Guardian Device rather than creating duplicates.
- [ ] Every successful bootstrap updates the active Guardian Device's last-seen and updated timestamps to backend server time.
- [ ] `primaryEmail` is updated when Clerk provides a value and is not used for lookup, ownership, merging, or authorization.
- [ ] Existing `primaryEmail` is preserved when Clerk provides no email.
- [ ] The Guardian installation ID is validated as a bounded opaque string.
- [ ] Bootstrap rejects invalid Guardian installation IDs without creating or updating domain records.
- [ ] Optional Guardian Device labels are accepted only as display metadata and are not used for identity or authorization.
- [ ] Tests cover idempotency, timestamp refresh, email update/preservation, and invalid-input behavior through the public mutation boundary.

## Blocked by

- .scratch/guardian-auth-bootstrap-backend/issues/01-create-minimal-guardian-auth-bootstrap-path.md
