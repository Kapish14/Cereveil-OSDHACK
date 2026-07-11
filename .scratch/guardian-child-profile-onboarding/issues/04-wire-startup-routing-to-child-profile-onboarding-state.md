Status: ready-for-agent

# Wire startup routing to Child Profile onboarding state

## Parent

.scratch/guardian-child-profile-onboarding/PRD.md

## What to build

Make Guardian startup/setup use real Child Profile onboarding state after auth bootstrap. Bootstrap can still use `hasChildProfiles` for the first coarse route, but once Guardian Mode reaches setup it should load Child Profile summaries and route between empty first-child onboarding and existing-profile setup/dashboard placeholder state. This prevents stale or boolean-only routing from hiding the newly created Unenrolled Child Profile.

## Acceptance criteria

- [ ] Guardian startup still respects auth/bootstrap loading, auth recovery, revoked device, device limit, and retryable error states.
- [ ] When bootstrap indicates no Child Profiles, Guardian setup loads Child Profile summaries before deciding what setup content to show.
- [ ] If the list is empty, Guardian setup shows first-child onboarding.
- [ ] If the list contains active Child Profiles, Guardian setup shows the existing Child Profile setup/dashboard placeholder state.
- [ ] Newly created Child Profiles are visible in the routing/setup state without app restart.
- [ ] Stale Child Profile state from a previous Guardian auth session is not reused for a different authenticated Guardian.
- [ ] Routing does not depend on dashboard analytics, location, notices, policy details, or Enrollment Code state.
- [ ] Tests cover zero-profile routing, existing-profile routing, post-create route refresh, stale-session clearing, and preservation of existing bootstrap error routes.

## Blocked by

- .scratch/guardian-child-profile-onboarding/issues/02-list-child-profiles-for-guardian-setup.md
- .scratch/guardian-child-profile-onboarding/issues/03-render-first-child-form-and-post-create-setup-state.md
