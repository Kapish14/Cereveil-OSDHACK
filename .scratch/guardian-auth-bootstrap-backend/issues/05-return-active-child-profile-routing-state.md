Status: ready-for-agent

# Return active Child Profile routing state

## Parent

.scratch/guardian-auth-bootstrap-backend/PRD.md

## What to build

Add the minimal backend support needed for Guardian auth bootstrap to tell the app whether the Household has active Child Profiles. The bootstrap response should support onboarding-versus-dashboard routing without returning Child details or other home-screen data.

## Acceptance criteria

- [ ] The schema supports the minimal Child Profile lifecycle state needed by bootstrap.
- [ ] Bootstrap returns `hasChildProfiles: false` when the Household has no Child Profiles.
- [ ] Bootstrap returns `hasChildProfiles: true` when the Household has at least one active Child Profile.
- [ ] Child Profiles in deleting state do not count toward `hasChildProfiles`.
- [ ] Bootstrap does not return Child Profile details, policy state, or dashboard/home-screen data.
- [ ] The active Child Profile lookup is indexed and bounded.
- [ ] Tests cover no profiles, active profiles, deleting-only profiles, and mixed active/deleting profiles through the public mutation boundary.

## Blocked by

- .scratch/guardian-auth-bootstrap-backend/issues/01-create-minimal-guardian-auth-bootstrap-path.md
