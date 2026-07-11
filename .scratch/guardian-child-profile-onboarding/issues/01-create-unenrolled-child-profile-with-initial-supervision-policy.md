Status: ready-for-agent

# Create Unenrolled Child Profile with Initial Supervision Policy

## Parent

.scratch/guardian-child-profile-onboarding/PRD.md

## What to build

Build the first end-to-end Child Profile creation path for Guardian Mode. An authenticated Guardian should be able to submit only a display name, birth month, and birth year; the backend should derive the Guardian Account and Household server-side, create an active Child Profile and privacy-default Initial Supervision Policy in one transaction, and return the new Unenrolled Child Profile summary plus current policy version metadata. Android should call this through a small fakeable client/repository seam so the create behavior can be tested without the real backend.

## Acceptance criteria

- [ ] `createChildProfile` requires an authenticated Guardian and derives Guardian Account and Household server-side.
- [ ] The create request accepts only child facts: display name, birth month, and birth year.
- [ ] The create request does not accept Clerk user ID, Guardian Account ID, Household ID, Guardian Device ID, or arbitrary owner IDs as authority.
- [ ] Disabled/deleting Guardian Accounts and deleting Households cannot create Child Profiles and return safe typed errors.
- [ ] Display name is validated as bounded and nonblank.
- [ ] Birth month and birth year are validated as real/plausible month-level values.
- [ ] The existing 8-15 target age range is enforced from birth month/year and backend server time.
- [ ] A successful create inserts an active Child Profile.
- [ ] A successful create inserts a privacy-default Initial Supervision Policy in the same transaction.
- [ ] The first policy version metadata is returned with the create response.
- [ ] The create response identifies the new profile as an Unenrolled Child Profile, with no Active Enrollment.
- [ ] Multiple Child Profiles can be created for the same Household.
- [ ] No numeric Child Profile cap is introduced.
- [ ] Android has a small fakeable create client/repository abstraction.
- [ ] Android create models send only display name, birth month, and birth year.
- [ ] Tests cover successful create, policy creation, authorization, invalid input, age bounds, and safe error mapping.

## Blocked by

None - can start immediately
