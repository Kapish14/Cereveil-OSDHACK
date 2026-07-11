Status: ready-for-agent

# Route Guardian startup from bootstrap state

## What to build

Use auth readiness and stored Guardian bootstrap state to drive Guardian Mode startup routing. The app should expose clear startup states for unauthenticated auth, bootstrap loading, setup/onboarding when the Household has no active Child Profiles, and the Guardian dashboard path when active Child Profiles exist.

## Acceptance criteria

- [ ] Unauthenticated Guardians are routed to the auth surface.
- [ ] Authenticated Guardians with bootstrap in progress see a startup/loading state.
- [ ] Successful bootstrap with no active Child Profiles routes to Guardian setup/onboarding.
- [ ] Successful bootstrap with active Child Profiles routes to the Guardian dashboard path or placeholder.
- [ ] Startup routing updates after each successful bootstrap so Child Profile presence changes are reflected on next launch.
- [ ] Stale bootstrap state from a previous Guardian Account is not reused for the wrong authenticated Guardian.
- [ ] Tests verify route state through the coordinator or view-model seam rather than Compose implementation details.

## Blocked by

- .scratch/guardian-auth-android-integration/issues/02-bootstrap-after-clerk-auth-readiness.md
