Status: ready-for-agent

# Guardian Child Profile Onboarding PRD

## Problem Statement

Guardian auth bootstrap can route an authenticated Guardian with no active Child Profiles into Guardian setup, but there is not yet a concrete Guardian Mode flow for creating the first Child Profile. Without this slice, the Guardian app can authenticate and bootstrap domain state but cannot move the Household into a supervised-child setup state, cannot create the Initial Supervision Policy required before enrollment, and cannot show a meaningful next step toward Child Device setup.

## Solution

Build the Guardian-side Child Profile onboarding slice. An authenticated Guardian will be able to create one or more Child Profiles by entering only a display name and birth month/year. The backend will derive the Guardian Account and Household from the authenticated Guardian server-side, validate the minimal child facts, create the Child Profile and its privacy-default Initial Supervision Policy in one transaction, and return the new Unenrolled Child Profile plus current policy version metadata. Guardian Mode will also be able to list active Child Profiles and show an Unenrolled Child Profile with a clear "set up child device" next action placeholder, without generating an Enrollment Code or touching Child Mode enrollment in this slice.

## User Stories

1. As a Guardian, I want setup to continue after auth bootstrap when my Household has no Child Profiles, so that signup does not stop at an empty setup screen.
2. As a Guardian, I want to create a Child Profile before pairing a Child Device, so that I can set up the Child identity independently from device enrollment.
3. As a Guardian, I want to enter only the Child's display name, so that onboarding avoids collecting unnecessary identity data.
4. As a Guardian, I want to enter only birth month and birth year, so that Cereveil does not store an exact birth date.
5. As a Guardian, I want Cereveil to avoid asking for the Child's legal name, so that the profile remains minimal.
6. As a Guardian, I want Cereveil to avoid asking for the Child's email, phone number, or login credentials, so that the Child Profile is not treated as a Child Account.
7. As a Guardian, I want avatar setup skipped for now, so that first-child onboarding stays short and does not require media storage work.
8. As a Guardian, I want Child Profile creation to work for more than one Child, so that one Household can supervise multiple Children.
9. As a Guardian, I want the first child flow and later child creation to use the same backend capability, so that the app does not have separate special-case rules for the first Child.
10. As a Guardian, I want the app to validate display name input, so that blank or unusable names are not saved.
11. As a Guardian, I want the app to validate birth month and birth year, so that invalid dates are caught before or during submission.
12. As a Guardian, I want Cereveil to enforce the target 8-15 age range from birth month/year, so that setup matches the intended product audience.
13. As a Guardian, I want age validation to be based on month-level boundaries, so that Cereveil can validate eligibility without exact birth dates.
14. As a Guardian, I want out-of-range age input to produce a stable, understandable error, so that I can correct the form without seeing backend internals.
15. As a Guardian, I want Child Profile creation to require my authenticated Guardian session, so that unauthenticated clients cannot create child data.
16. As a Guardian, I want the backend to use my authenticated Guardian Account and Household automatically, so that the app does not ask me to choose backend IDs.
17. As a Guardian, I want the Android client to send only child facts when creating a Child Profile, so that Household ownership cannot be spoofed from the client.
18. As a Guardian, I want the Child Profile and its Initial Supervision Policy created together, so that the Child Profile is never active without a supervision baseline.
19. As a Guardian, I want the Initial Supervision Policy to use privacy-default settings, so that optional monitoring features do not silently start enabled.
20. As a Guardian, I want an Unenrolled Child Profile to be visible after creation, so that I can see setup progress before device enrollment.
21. As a Guardian, I want the app to show that the Child Device still needs setup, so that I know the Child is not yet actively supervised.
22. As a Guardian, I want the next action after Child Profile creation to point toward setting up the Child Device, so that the path forward is obvious.
23. As a Guardian, I want the Child Device setup action to be only a placeholder in this slice, so that Enrollment Code work does not block Child Profile creation.
24. As a Guardian, I want the app to list active Child Profiles, so that setup and later dashboard routing can use real child data instead of only a boolean.
25. As a Guardian, I want deleting Child Profiles excluded from ordinary setup lists, so that profiles being removed do not appear as active setup targets.
26. As a Guardian, I want the list response to avoid dashboard analytics, location, notices, or policy details, so that first onboarding stays narrow and private.
27. As a Guardian, I want the app to update after a successful create, so that I do not have to restart to see the new Child Profile.
28. As a Guardian, I want startup routing to stop sending me to empty first-child onboarding once an active Child Profile exists, so that setup feels complete for this stage.
29. As a Guardian, I want backend errors to be mapped to stable app states or form errors, so that raw Convex exception payloads are not shown.
30. As a Guardian, I want retryable network failures to be recoverable, so that poor connectivity does not force me through auth again.
31. As a Guardian using a shared Guardian Device, I want Child Profile data to belong only to the currently authenticated Guardian Account's Household, so that stale state from another Guardian session is not reused.
32. As a developer, I want Child Profile creation implemented outside Guardian auth bootstrap, so that authentication remains separate from supervision setup.
33. As a developer, I want a focused Child Profile backend module, so that Child Profile lifecycle logic does not accumulate inside the Guardian auth module.
34. As a developer, I want the backend to derive the Guardian actor from Convex auth, so that client-provided Guardian Account or Household IDs are never trusted as authority.
35. As a developer, I want the create mutation to return the profile and current policy version reference, so that tests can verify the transactional invariant.
36. As a developer, I want a focused list query for Guardian Mode, so that UI screens can render Child Profile summaries without fetching unrelated supervision data.
37. As a developer, I want Android client abstractions for Child Profile create/list calls, so that UI and routing tests can use fakes.
38. As a developer, I want the highest practical test seam to be the backend public function boundary and the Guardian onboarding view-model/coordinator boundary, so that tests cover behavior rather than helper structure.
39. As a developer, I want this slice to avoid Enrollment Code generation, so that Child Device enrollment remains a separate PRD.
40. As a developer, I want this slice to avoid Child Mode, Android Keystore device keying, and Child Device credentials, so that the Guardian onboarding path can ship independently.

## Implementation Decisions

- Implement a Guardian Child Profile onboarding slice after Guardian auth bootstrap.
- Keep Child Profile creation out of Guardian auth bootstrap.
- Create a focused backend feature module for Child Profile operations rather than adding this work to the Guardian auth module.
- Add or complete backend schema support for versioned Supervision Policies if it is not already implemented in code.
- Preserve the existing `childProfiles` model shape: Household ownership, display name, birth month, birth year, optional avatar key for future use, lifecycle status, and lifecycle timestamps.
- Do not include avatar capture, upload, or selection in this slice.
- Treat avatar as future optional device-local or key-based display metadata, not a required backend field for onboarding.
- Add a Guardian-authenticated `createChildProfile` mutation.
- The create mutation accepts only child facts from the client: `displayName`, `birthMonth`, and `birthYear`.
- The create mutation must not accept Clerk user ID, Guardian Account ID, Household ID, Guardian Device ID, or arbitrary owner IDs from the client for authorization.
- Resolve the authenticated Guardian server-side through Convex auth and the Guardian Account/Household relationship.
- Require an active Guardian Account and active Household before creating a Child Profile.
- Block creation for disabled/deleting Guardian Accounts or deleting Households using safe typed errors.
- Validate `displayName` as a bounded nonblank display value.
- Validate `birthMonth` as a real month and `birthYear` as a plausible year.
- Enforce the existing 8-15 target age range using birth month/year and backend server time.
- Treat age validation as product eligibility, not exact identity verification.
- Create the Child Profile and Initial Supervision Policy in the same backend transaction.
- Maintain the invariant that an active Child Profile created through this flow has an Initial Supervision Policy.
- Use privacy-default settings for the Initial Supervision Policy.
- The Initial Supervision Policy should not silently enable optional monitoring or collection-heavy features.
- Store Supervision Policy as immutable versioned state, matching the existing versioned-policy ADR.
- The first policy version should be version `1` for the Child Profile unless the implementation already uses a different equivalent convention.
- Return the newly created Child Profile summary and current policy version reference from `createChildProfile`.
- The create response should include Child Profile ID, display name, birth month, birth year, status, enrollment summary, current policy version metadata, and backend server time when useful.
- Represent a newly created profile with no Active Enrollment as an Unenrolled Child Profile.
- Add a Guardian-authenticated `listChildProfiles` query.
- The list query should return active Child Profiles for the authenticated Guardian's Household.
- The list query should exclude Child Profiles in deleting state from ordinary setup/dashboard lists.
- The list query should return only summary data needed by Guardian onboarding: Child Profile ID, display name, birth month/year, status, enrollment summary, and current policy version metadata if needed.
- Do not return dashboard analytics, location state, Guardian Notices, Safety Alerts, Screen Time Summaries, or full Supervision Policy details from the list query.
- Support multiple Child Profiles per Household.
- Do not add a numeric Child Profile cap in this slice.
- If a future cap is needed for pricing, abuse prevention, or UX, decide it separately and document it explicitly.
- Add Android Guardian Mode client/repository models for creating and listing Child Profiles.
- Keep Android client calls hidden behind a small interface so Guardian onboarding UI can be tested without real Convex.
- Add Guardian first-child form UI for display name and birth month/year.
- Route Guardians with no Child Profiles from setup into the first-child form.
- After successful creation, show the Unenrolled Child Profile in setup.
- Show a clear next action placeholder for setting up the Child Device.
- Do not generate Enrollment Codes in this slice.
- Do not begin Child Mode Protection Setup from this slice.
- Map backend validation/auth errors into stable UI-facing errors or states.
- Avoid displaying raw backend exception payloads or internal identifiers in Android UI.
- Refresh local or in-memory Guardian onboarding state after create/list succeeds so the new Child Profile is visible immediately.

## Testing Decisions

- Test external behavior at the highest practical seams rather than private helper decomposition.
- Backend tests should exercise the public create/list function boundary with authenticated Guardian identity.
- Backend tests should verify that `createChildProfile` derives Guardian Account and Household server-side.
- Backend tests should verify that client-supplied owner IDs are neither accepted nor needed.
- Backend tests should verify successful Child Profile creation with display name, birth month, and birth year.
- Backend tests should verify that creating a Child Profile also creates an Initial Supervision Policy in the same transaction.
- Backend tests should verify the first policy version metadata returned by the create response.
- Backend tests should verify privacy-default policy settings at the behavior/shape level needed for downstream enrollment.
- Backend tests should verify that active Child Profiles are returned by `listChildProfiles`.
- Backend tests should verify that deleting Child Profiles are excluded from normal list results.
- Backend tests should verify multiple Child Profiles can be created for one Household.
- Backend tests should verify age validation for in-range, too-young, and too-old month/year inputs.
- Backend tests should verify invalid birth month, implausible year, and blank/oversized display name handling.
- Backend tests should verify disabled/deleting Guardian Accounts and deleting Households cannot create Child Profiles.
- Backend tests should verify unauthenticated requests fail safely.
- Android tests should use a fake Child Profile client/repository at the Guardian onboarding view-model or coordinator boundary.
- Android tests should verify the first-child form emits a create request containing only display name, birth month, and birth year.
- Android tests should verify validation or mapped error states for missing name and invalid birth data.
- Android tests should verify successful creation transitions to showing an Unenrolled Child Profile.
- Android tests should verify the post-create screen shows a Child Device setup placeholder without invoking Enrollment Code generation.
- Android tests should verify list/load behavior for zero Child Profiles and one or more Child Profiles.
- Android tests should follow the existing Guardian auth coordinator/client test style where possible: fakes for backend clients and state repositories, assertions on externally visible routes/state, and no assertions on private coroutine or helper structure.
- Compose or instrumented tests should be added only where form rendering or navigation cannot be validated through a plain JVM seam.

## Out of Scope

- Enrollment Code generation.
- QR code display or scanning.
- Child Mode Protection Setup.
- Prepared Child Device creation.
- Child Device key generation or Android Keystore integration.
- Convex Device Identity module implementation.
- Child Device Credential creation, refresh, rotation, or revocation.
- Active Enrollment creation.
- Role Lock activation.
- Child Device FCM registration.
- Guardian dashboard analytics, location, Safety Alerts, Screen Time Summaries, Access Requests, or policy editing.
- Full Supervision Policy editor UI.
- Avatar capture, upload, selection, or backend media storage.
- End Supervision.
- Replace Child Device.
- Pricing or product caps for number of Child Profiles per Household.
- Production observability beyond safe errors/logging needed for this slice.

## Further Notes

- This PRD follows the existing domain language: Guardian, Guardian Account, Household, Child, Child Profile, Unenrolled Child Profile, Initial Supervision Policy, Supervision Policy, Child Device, Prepared Child Device, Enrollment Code, and Active Enrollment.
- This PRD follows ADR-0005 by allowing one Household to supervise multiple Children.
- This PRD follows ADR-0009 by targeting children aged 8-15.
- This PRD follows ADR-0027 by minimizing Child Profile identity data.
- This PRD follows ADR-0034 by requiring backend authorization checks for every public Convex operation.
- This PRD follows ADR-0035 by treating Supervision Policy as versioned authoritative state.
- This PRD follows ADR-0047 by keeping Child Profiles and Supervision Policies as domain-oriented Convex tables rather than embedding them in Household state.
- This PRD follows ADR-0051 by keeping Child Profiles and supervision data out of Guardian auth bootstrap.
- This PRD follows ADR-0052 by creating a Child Profile and initial policy before Child Device enrollment.
- This PRD follows ADR-0053 by leaving Enrollment Code exchange and Child Device credentialing to a later enrollment slice.
