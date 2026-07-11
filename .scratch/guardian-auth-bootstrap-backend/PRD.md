Status: ready-for-agent

# Guardian Auth Bootstrap Backend PRD

## Problem Statement

Guardian Mode can authenticate a Guardian through Clerk, but Cereveil does not yet have the backend slice that turns that Clerk session into Cereveil domain state. Without this slice, the Guardian app cannot establish its Guardian Account, Household, or Guardian Device in Convex, cannot safely route between onboarding and dashboard states, and cannot rely on consistent backend authorization primitives for later Guardian APIs.

## Solution

Implement the backend-first Guardian auth bootstrap slice in Convex. After Clerk authentication, Guardian Mode will be able to call an idempotent `bootstrapGuardian` mutation that derives identity server-side, finds or creates the Guardian Account, Household, and current Guardian Device, enforces Guardian Device lifecycle rules, and returns a small internal bootstrap state for app routing and follow-up Convex calls.

This PRD intentionally stops at the backend API and schema. Android-side invocation will be implemented in a later slice after the Convex API compiles and generated types are stable.

## User Stories

1. As a Guardian, I want my Clerk sign-in to establish my Cereveil Guardian Account, so that the app can connect authentication to supervision state.
2. As a Guardian, I want first signup to create my Household automatically, so that I can proceed into Cereveil onboarding without manual backend setup.
3. As a Guardian, I want the same app installation to be recognized across app launches, so that repeated startup does not create duplicate Guardian Devices.
4. As a Guardian, I want the backend to distinguish my Guardian Account from my Guardian Device, so that device revocation and account authorization remain separate.
5. As a Guardian, I want my device to have a readable label, so that device-management screens can later show understandable names.
6. As a Guardian, I want Cereveil to avoid using hardware IDs, so that device identity does not rely on sensitive phone identifiers.
7. As a Guardian, I want a revoked Guardian Device to stay revoked, so that revocation cannot be undone by signing in again.
8. As a Guardian, I want Cereveil to limit my Guardian Account to two active Guardian Devices, so that account access remains bounded.
9. As a Guardian, I want a clear error if I try to add a third Guardian Device, so that the app can later guide me to manage devices.
10. As a Guardian, I want bootstrap to be safe to retry, so that network retries, app restarts, and resumed sessions do not corrupt state.
11. As a Guardian, I want bootstrap to refresh the current Guardian Device's last-seen time, so that the backend has current operational state.
12. As a Guardian, I want bootstrap to avoid creating Child Profiles or supervision data, so that authentication does not imply setup has completed.
13. As a Guardian, I want the app to know whether active Child Profiles exist, so that it can route me to onboarding or the dashboard.
14. As a Guardian, I want only active Child Profiles to count for routing, so that deleting profiles do not make setup look complete.
15. As a Guardian, I want my Household timezone to be set during first bootstrap, so that later policy scheduling can use a sensible default.
16. As a Guardian, I want the initial backend to default to India-specific configuration, so that the first implementation matches the current product scope.
17. As a Guardian, I want changed Clerk email metadata to update safely, so that profile metadata can stay current without becoming my identity key.
18. As a Guardian, I want missing Clerk email metadata not to erase existing metadata, so that temporary provider omissions do not damage my record.
19. As a disabled Guardian Account holder, I should not be able to recreate or refresh domain state through bootstrap, so that account disablement is meaningful.
20. As a deleting Guardian Account holder, I should not be able to interrupt deletion by signing in again, so that cleanup remains reliable.
21. As a developer, I want Guardian Account lookup to use Convex `identity.tokenIdentifier`, so that authorization follows the project's Convex auth guidance.
22. As a developer, I want bootstrap to derive identity server-side, so that clients cannot spoof Clerk user IDs, Guardian Account IDs, Household IDs, or Guardian Device IDs.
23. As a developer, I want bootstrap to expose a narrow response shape, so that later app screens fetch display data through focused queries.
24. As a developer, I want app-visible errors to be typed and safe, so that Android can handle auth, lifecycle, and device-limit failures consistently.
25. As a developer, I want shared auth and error helpers introduced minimally, so that bootstrap does not overbuild the full Guardian wrapper framework.
26. As a developer, I want the later `guardianMutation` wrapper to remain a post-bootstrap concern, so that bootstrap can create the Guardian Account instead of requiring one.
27. As a developer, I want the backend environment derived server-side, so that the client cannot decide whether a row is development or production.
28. As a developer, I want the first backend environment to be `dev` only, so that production rules can be added deliberately later.
29. As a developer, I want app-driven bootstrap instead of Clerk webhooks for this slice, so that local development avoids webhook signing, retries, and duplicate delivery complexity.
30. As a developer, I want the public mutation boundary to be the main test seam, so that tests verify externally visible behavior rather than private helper details.

## Implementation Decisions

- Implement a backend-first Convex slice for Guardian auth bootstrap only. Android invocation and UI handling are out of scope for this PRD.
- Define the initial Guardian auth schema around `guardianAccounts`, `households`, and `guardianDevices`, plus the minimal Child Profile support needed to compute whether active Child Profiles exist.
- Use Convex `identity.tokenIdentifier` as the stable auth-linked key for Guardian Accounts.
- Store Clerk user ID only as optional provider-specific metadata when available. Do not use email or Clerk user ID as the stable authorization key.
- Treat `primaryEmail` as non-authoritative metadata. Bootstrap may update it when Clerk provides a value, must not use it for lookup or authorization, and must not clear an existing value when Clerk provides none.
- Implement `bootstrapGuardian` as a public Convex mutation in the Guardian auth module.
- `bootstrapGuardian` derives Clerk identity from `ctx.auth.getUserIdentity()` server-side.
- `bootstrapGuardian` must not accept Clerk user ID, email, Guardian Account ID, Household ID, or Guardian Device ID from the client for authorization or ownership.
- Client arguments are limited to app/device facts: opaque Guardian installation ID, optional device label, app build, and optional timezone.
- The Guardian installation ID is an opaque app-generated value used to recognize the same Guardian app installation. It is not an auth credential and is not user-facing.
- Convex validates the Guardian installation ID as a bounded opaque string.
- Do not use IMEI, Android ID, advertising ID, serial number, phone number, or hardware identifiers for Guardian Device identity.
- `deviceLabel` is optional display metadata. The app may later suggest a brand/model-style label, and the Guardian may rename it in a future device-management flow.
- Backend environment is derived server-side. The first implementation records `dev` only.
- For a new Guardian Account, bootstrap creates exactly one active Guardian Account, one active Household, and one active Guardian Device.
- Bootstrap does not create Child Profiles, Supervision Policies, Enrollment Codes, FCM tokens, or supervision data.
- FCM token registration remains a separate future flow.
- Bootstrap is idempotent. Repeated calls for the same active Guardian Account and Guardian installation return the same domain records and refresh the Guardian Device's last-seen state.
- Every successful bootstrap updates the active Guardian Device's `lastSeenAt` and `updatedAt` to backend server time.
- Guardian Device rows are keyed within a Guardian Account by the Guardian installation ID.
- If the same Guardian installation has a revoked Guardian Device row, bootstrap fails with `DEVICE_REVOKED` and does not reactivate it.
- A new Guardian Device may be created only if the Guardian Account has fewer than two active Guardian Devices.
- If a third active Guardian Device would be created, bootstrap fails with `DEVICE_LIMIT_REACHED`.
- Bootstrap must not auto-revoke older Guardian Devices.
- If an existing Guardian Account is `disabled` or `deleting`, bootstrap fails with a safe typed error and does not create or update Household or Guardian Device records.
- If an active Guardian Account has no Household, bootstrap may create one to repair incomplete first-run state.
- If the Guardian Account's Household is `deleting`, bootstrap fails with a safe typed error and does not create a replacement Household.
- Bootstrap accepts an optional IANA timezone when creating a Household, defaults to `Asia/Kolkata` when missing, and sets country to `IN` server-side.
- Do not infer Household timezone or country from GPS or location.
- Bootstrap returns a small internal `GuardianBootstrapState` containing Guardian Account ID, Household ID, Guardian Device ID, Guardian Device status, whether active Child Profiles exist, and server time.
- `hasChildProfiles` is true only when the Household has at least one active Child Profile. Child Profiles in `deleting` do not count.
- Bootstrap does not return raw Clerk identity, all Child data, FCM tokens, Supervision Policy state, or home-screen display data.
- Use app-driven bootstrap rather than Clerk webhooks for initial Guardian Account provisioning.
- Introduce only the shared primitives needed for this slice, such as Clerk identity extraction, safe application errors, and time helpers.
- Implement bootstrap as a direct Convex mutation because it creates the Guardian Account. The full `guardianMutation` wrapper is introduced later with the first post-bootstrap Guardian API that requires an existing active Guardian Account.
- Respect the existing Convex guidelines: define validators for all functions, define schema in the Convex schema file, use indexes instead of filters, bound data reads, and derive authorization identity server-side.

## Testing Decisions

- The primary test seam is the public Convex mutation boundary for `bootstrapGuardian`.
- Tests should exercise externally visible behavior: returned bootstrap state, created or updated database rows, and safe typed errors.
- Tests should not assert private helper internals or the exact decomposition of use-case/data/helper functions.
- Test unauthenticated calls return the expected safe unauthenticated error.
- Test first bootstrap creates Guardian Account, Household, and Guardian Device only.
- Test repeated bootstrap for the same installation is idempotent and updates Guardian Device last-seen state.
- Test Guardian Account lookup uses the stable token identifier behavior rather than email.
- Test email metadata update and no-email preservation behavior.
- Test missing Household repair for an active Guardian Account.
- Test `deleting` Household blocks bootstrap.
- Test disabled/deleting Guardian Accounts block bootstrap without mutating Household or Guardian Device records.
- Test active Child Profile presence sets `hasChildProfiles` true.
- Test deleting Child Profiles do not count for `hasChildProfiles`.
- Test revoked Guardian Device rows block bootstrap with `DEVICE_REVOKED`.
- Test adding a new third Guardian Device fails with `DEVICE_LIMIT_REACHED`.
- Test existing active Guardian Devices are not auto-revoked.
- Test timezone defaulting to `Asia/Kolkata` and server-side country `IN`.
- Test invalid client arguments are rejected by validators.
- Use existing project test conventions where available. If no Convex test harness exists yet, this slice may introduce the smallest practical backend test harness around Convex function execution.

## Out of Scope

- Android-side Clerk-to-Convex integration.
- Persisting or generating the Guardian installation ID in Android.
- Calling `bootstrapGuardian` from Guardian Mode.
- Guardian app UI for onboarding, dashboard routing, or error display.
- Device-management screens.
- Reactivating revoked Guardian Devices.
- Revoking Guardian Devices.
- Pairing additional Guardian Devices through Guardian Pairing Codes.
- FCM token registration.
- Child Profile creation UI or API beyond the minimal schema/read support needed for `hasChildProfiles`.
- Supervision Policy creation.
- Enrollment Code creation.
- Child Device enrollment.
- Clerk webhook provisioning.
- Production environment support beyond server-derived `dev` metadata.
- Full `guardianMutation` wrapper for post-bootstrap Guardian APIs.

## Further Notes

- This PRD follows the existing domain language: Guardian, Guardian Account, Household, Guardian Device, Child Profile, Guardian Mode, and Child Mode.
- The decisions align with the existing ADRs for Convex as the primary backend, authorization by actor and resource, separate Guardian and Child identities, domain-oriented Convex tables, backend structure around feature modules and wrappers, and Guardian domain bootstrap after Clerk authentication.
- The local docs have already been updated to capture the key bootstrap decisions in the backend data model, backend structure, and Guardian bootstrap ADR.
