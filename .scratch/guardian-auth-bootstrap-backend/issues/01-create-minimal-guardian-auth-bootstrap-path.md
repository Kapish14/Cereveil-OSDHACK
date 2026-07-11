Status: ready-for-agent

# Create the minimal Guardian auth bootstrap path

## Parent

.scratch/guardian-auth-bootstrap-backend/PRD.md

## What to build

Build the first usable backend path for Guardian auth bootstrap. After Clerk authentication, a Guardian app can call the public bootstrap mutation and receive internal app-routing state backed by newly created or loaded Guardian Account, Household, and Guardian Device records. The function derives Clerk identity server-side, records the backend environment as development-only for now, applies Household timezone defaults, and returns only the narrow bootstrap state needed for follow-up app calls.

## Acceptance criteria

- [ ] The Convex schema includes the initial Guardian Account, Household, and Guardian Device tables with the indexes needed by bootstrap.
- [ ] The public bootstrap mutation requires Convex authentication and derives identity from the server-side auth context.
- [ ] The mutation does not accept Clerk user ID, email, Guardian Account ID, Household ID, or Guardian Device ID from the client.
- [ ] A first successful bootstrap creates exactly one active Guardian Account, one active Household, and one active Guardian Device.
- [ ] The Guardian Account is keyed by Convex's stable token identifier rather than email.
- [ ] The Household is created with server-side country `IN` and a timezone default of `Asia/Kolkata` when no valid timezone is provided.
- [ ] The Guardian Device records server-derived development environment metadata.
- [ ] The mutation returns only Guardian Account ID, Household ID, Guardian Device ID, Guardian Device status, active Child Profile presence, and server time.
- [ ] Tests cover the successful first-bootstrap path and unauthenticated rejection through the public mutation boundary.

## Blocked by

None - can start immediately.
