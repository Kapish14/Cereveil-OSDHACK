Status: ready-for-agent

# Enforce Guardian Account and Household lifecycle rules

## Parent

.scratch/guardian-auth-bootstrap-backend/PRD.md

## What to build

Make Guardian auth bootstrap respect Guardian Account and Household lifecycle state. Blocked or deleting domain state must not be refreshed or recreated by signing in again, while incomplete first-run state can be repaired when it is safe.

## Acceptance criteria

- [ ] Bootstrap fails with a safe typed error when the Guardian Account is disabled.
- [ ] Bootstrap fails with a safe typed error when the Guardian Account is deleting.
- [ ] Disabled or deleting Guardian Account bootstrap attempts do not create or update Household or Guardian Device records.
- [ ] An active Guardian Account with no Household is repaired by creating one.
- [ ] Bootstrap fails with a safe typed error when the Guardian Account's Household is deleting.
- [ ] A deleting Household is not replaced by a new Household during bootstrap.
- [ ] Tests cover each lifecycle branch and verify both returned errors and absence of forbidden mutations.

## Blocked by

- .scratch/guardian-auth-bootstrap-backend/issues/01-create-minimal-guardian-auth-bootstrap-path.md
