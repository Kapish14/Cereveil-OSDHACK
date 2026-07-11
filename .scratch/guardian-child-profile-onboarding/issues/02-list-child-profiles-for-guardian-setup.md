Status: ready-for-agent

# List Child Profiles for Guardian Setup

## Parent

.scratch/guardian-child-profile-onboarding/PRD.md

## What to build

Build the narrow Guardian Mode path for loading active Child Profile summaries after auth bootstrap. The authenticated Guardian should be able to list active Child Profiles for their Household, including Unenrolled Child Profiles and current policy version summary data needed by setup, without returning dashboard analytics, location, notices, safety data, or full Supervision Policy details. Android should expose this through the same fakeable Child Profile client/repository seam so setup state can be tested with zero, one, or multiple profiles.

## Acceptance criteria

- [ ] `listChildProfiles` requires an authenticated Guardian and derives Household ownership server-side.
- [ ] The list query does not accept Guardian Account ID, Household ID, or arbitrary owner IDs as authority.
- [ ] The list query returns active Child Profiles for the authenticated Guardian's Household.
- [ ] Child Profiles in deleting state are excluded from ordinary setup list results.
- [ ] Returned summaries include Child Profile ID, display name, birth month, birth year, profile status, enrollment summary, and current policy version metadata when needed.
- [ ] Unenrolled Child Profiles are represented clearly in the returned summary.
- [ ] The response does not include dashboard analytics, location state, Guardian Notices, Safety Alerts, Screen Time Summaries, or full Supervision Policy details.
- [ ] The query handles zero, one, and multiple active Child Profiles.
- [ ] Android has a fakeable list/load path for Child Profile summaries.
- [ ] Android setup state can distinguish no Child Profiles from one or more Child Profiles.
- [ ] Tests cover authorization, empty list, multiple profiles, deleting-profile exclusion, and response narrowness.

## Blocked by

- .scratch/guardian-child-profile-onboarding/issues/01-create-unenrolled-child-profile-with-initial-supervision-policy.md
