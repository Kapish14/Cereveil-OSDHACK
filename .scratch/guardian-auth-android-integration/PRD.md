Status: ready-for-agent

# Guardian Auth Android Integration PRD

## Problem Statement

Guardian Mode can authenticate a Guardian through Clerk and the Convex backend now exposes the Guardian auth bootstrap mutation, but the Android app does not yet connect those pieces. After sign-in, the Guardian app still cannot identify the current Guardian Device, call backend bootstrap, persist the returned bootstrap state, or route the Guardian to the correct next screen. Without this slice, Guardian authentication remains disconnected from Cereveil domain state on Android.

## Solution

Implement the Guardian Mode Android integration for Guardian auth bootstrap. The app will generate and persist a local `guardianInstallationId`, build safe app/device metadata, wait until Clerk auth is ready, call the Convex `bootstrapGuardian` mutation, store the returned Guardian bootstrap state, and expose a small routing state that can send the Guardian toward setup, dashboard, or a recoverable error screen.

This PRD assumes the backend contract from the Guardian Auth Bootstrap Backend PRD is already implemented and stable.

## User Stories

1. As a Guardian, I want the Guardian app to remember this installation, so that restarting or reopening the app does not create duplicate Guardian Devices.
2. As a Guardian, I want the Guardian app to generate its own installation ID, so that Cereveil does not depend on hardware identifiers.
3. As a Guardian, I want the installation ID to survive normal app restarts, so that the backend recognizes the same Guardian Device.
4. As a Guardian, I want the installation ID to be separate from my Clerk session, so that signing out and signing in again does not create a new Guardian Device on the same app installation.
5. As a Guardian, I want the installation ID to be opaque, so that it does not expose sensitive phone details.
6. As a Guardian, I want the app to send a readable device label, so that future device-management screens can show an understandable name.
7. As a Guardian, I want the device label to be built from brand and model when available, so that I can recognize the device without typing anything during signup.
8. As a Guardian, I want the app to handle missing or duplicated brand/model values gracefully, so that the label still looks reasonable across Android devices.
9. As a Guardian, I want the app to send app build metadata, so that backend records can distinguish the client version that last bootstrapped the Guardian Device.
10. As a Guardian, I want the app to send the local timezone, so that new Household scheduling defaults can be created from a sensible device setting.
11. As a Guardian, I want the app to avoid using GPS or location permissions for timezone, so that auth bootstrap does not request unnecessary sensitive access.
12. As a Guardian, I want the app to wait until Clerk authentication is ready, so that bootstrap runs with a valid Guardian Account identity.
13. As a Guardian, I want the app to call bootstrap automatically after sign-in, so that I do not have to perform a separate setup step just to create domain state.
14. As a Guardian, I want bootstrap retries to be safe, so that temporary network failures or app restarts do not corrupt my Guardian Account state.
15. As a Guardian, I want the app to show a loading state while bootstrap is in progress, so that the app does not appear stuck after sign-in.
16. As a Guardian, I want successful bootstrap state stored locally, so that the app can make immediate routing decisions.
17. As a Guardian, I want the app to know whether active Child Profiles exist, so that it can route me to onboarding or the Guardian dashboard.
18. As a Guardian with no active Child Profiles, I want to land in Guardian setup, so that I can create or enroll the first Child Profile.
19. As a Guardian with active Child Profiles, I want to land in the Guardian dashboard, so that I can supervise without repeating onboarding.
20. As a Guardian, I want the app to keep the returned Guardian Account, Household, and Guardian Device references available to later Guardian Mode features, so that later API calls do not need to rediscover bootstrap state.
21. As a Guardian, I want the app to handle an unauthenticated bootstrap response by returning me to auth, so that I can recover from expired or missing sessions.
22. As a Guardian, I want the app to handle a revoked Guardian Device clearly, so that a device that has been removed from the Guardian Account cannot silently regain access.
23. As a Guardian, I want the app to handle the two-device limit clearly, so that I understand why this installation cannot join the Guardian Account.
24. As a Guardian, I want bootstrap errors to be stable and understandable, so that the app can present consistent recovery routes.
25. As a Guardian, I want retryable network errors to allow retry, so that poor connectivity does not force me through sign-in again.
26. As a Guardian, I want non-retryable account or device errors to stop automatic retry loops, so that the app does not repeatedly call the backend for a blocked state.
27. As a Guardian, I want the app to avoid showing raw backend error payloads, so that internal identifiers and implementation details are not exposed.
28. As a Guardian, I want the app to continue respecting Guardian Mode and Child Mode separation, so that Child Mode never depends on Clerk-specific Guardian auth code.
29. As a Guardian, I want the Guardian bootstrap flow to be part of startup routing, so that deep links or later screens do not run before domain state is ready.
30. As a Guardian, I want local bootstrap state to update after each successful bootstrap, so that device revocation or Child Profile changes are reflected on next launch.
31. As a Guardian, I want sign-out to clear sensitive local routing state without deleting the installation ID, so that the same app installation can be recognized after a future sign-in.
32. As a Guardian using a shared device, I want a different Clerk account sign-in to be handled deliberately, so that stale bootstrap state from a previous Guardian Account is not reused for the wrong account.
33. As a developer, I want a narrow Android bootstrap coordinator, so that Clerk readiness, local installation storage, Convex invocation, and routing can be tested together.
34. As a developer, I want pure builders for installation metadata, so that device label, build metadata, and timezone behavior can be tested without Android UI.
35. As a developer, I want Convex bootstrap invocation hidden behind a small Guardian auth client interface, so that app startup logic is not coupled to low-level request details.
36. As a developer, I want local storage hidden behind a small repository interface, so that persistence can move from simple preferences to a fuller database later without changing routing logic.
37. As a developer, I want errors mapped into a Guardian bootstrap error model, so that UI and routing do not switch on raw transport exceptions.
38. As a developer, I want tests to verify behavior at the coordinator boundary, so that implementation details can change without breaking tests.
39. As a developer, I want the Android slice to use the generated Convex API path for `api.modules.guardianAuth.public.bootstrapGuardian`, so that the client calls the same backend contract covered by backend tests.
40. As a developer, I want this slice to avoid adding Child Profile creation, FCM registration, or Guardian dashboard data fetching, so that auth bootstrap remains the only integration being implemented.

## Implementation Decisions

- Implement an Android Guardian auth bootstrap slice in Guardian Mode only.
- Keep Clerk-specific integration out of Child Mode and out of shared domain models.
- Generate `guardianInstallationId` locally on first use using a random opaque identifier.
- Persist `guardianInstallationId` in local app storage that survives app restarts and normal sign-out.
- Do not use IMEI, Android ID, advertising ID, serial number, phone number, MAC address, or any hardware identifier for Guardian Device identity.
- Treat `guardianInstallationId` as an identifier, not a secret or credential.
- Preserve the existing installation ID when the Guardian signs out.
- Clear or invalidate account-specific bootstrap state on sign-out so stale Guardian Account routing state is not reused.
- Build `deviceLabel` from Android brand and model metadata.
- Normalize `deviceLabel` enough to avoid duplicated labels such as repeated brand/model strings and blank labels.
- Keep `deviceLabel` as display metadata only. It must not be used for authorization or local identity.
- Read app build metadata from app build configuration or package metadata.
- Read timezone from the device's current IANA timezone setting.
- Do not request location permission or use location data to determine timezone.
- After Clerk auth is ready for the Guardian flavor, call the Convex mutation `api.modules.guardianAuth.public.bootstrapGuardian`.
- Send only the backend-approved client facts: `guardianInstallationId`, optional `deviceLabel`, app build, and optional timezone.
- Do not send Clerk user ID, email, Guardian Account ID, Household ID, or Guardian Device ID as bootstrap authority.
- Store the returned Guardian bootstrap state locally through a small repository abstraction.
- The stored state should include the Guardian Account ID, Household ID, Guardian Device ID, Guardian Device status, whether active Child Profiles exist, and the backend server time if returned.
- Expose a routing state derived from auth readiness plus bootstrap state.
- Route unauthenticated Guardians to the auth surface.
- Route authenticated Guardians with bootstrap in progress to a startup/loading surface.
- Route successfully bootstrapped Guardians with no active Child Profiles to Guardian setup/onboarding.
- Route successfully bootstrapped Guardians with active Child Profiles to the Guardian dashboard.
- Map `UNAUTHENTICATED` to an auth recovery path.
- Map `DEVICE_REVOKED` to a blocked-device path that does not automatically retry bootstrap.
- Map `DEVICE_LIMIT_REACHED` to a blocked-device-limit path that does not automatically retry bootstrap.
- Map retryable network or service failures to a retryable bootstrap error state.
- Avoid displaying raw backend exception payloads directly in UI.
- Keep retry policy bounded and explicit; do not run uncontrolled automatic retry loops for permanent errors.
- Introduce a Guardian bootstrap coordinator or view model as the primary orchestration boundary.
- Introduce a Guardian auth client abstraction that owns the Convex mutation call and result/error mapping.
- Introduce a local Guardian installation/bootstrap state repository abstraction for persistence.
- Keep pure value-building logic for installation ID validation, device label, app build metadata, and timezone separate from Compose UI.
- Reuse the existing Android module direction: shared non-UI models and interfaces may live in core modules, while Guardian-specific orchestration and UI routing live in Guardian Mode.
- Do not introduce backend schema changes in this slice.
- Do not introduce Child Mode bootstrap behavior in this slice.

## Testing Decisions

- The primary test seam is the Guardian bootstrap coordinator or view model boundary.
- Coordinator tests should verify externally visible behavior: when bootstrap is called, what arguments are sent, what state is persisted, and what route/error state is emitted.
- Tests should use fake auth readiness, fake Guardian auth client, fake local state repository, and fake metadata providers.
- Tests should not assert private helper decomposition, internal coroutine structure, or exact persistence implementation details.
- Test first launch generates and persists a `guardianInstallationId`.
- Test a later launch reuses the existing `guardianInstallationId`.
- Test sign-out clears account-specific bootstrap state while preserving `guardianInstallationId`.
- Test the bootstrap call waits for Clerk-authenticated readiness.
- Test the bootstrap call includes `guardianInstallationId`, device label, app build metadata, and timezone.
- Test the bootstrap call does not accept or forward client-supplied Guardian Account, Household, Guardian Device, Clerk user ID, or email authority.
- Test successful bootstrap persists the returned bootstrap state.
- Test successful bootstrap with no active Child Profiles routes to Guardian setup/onboarding.
- Test successful bootstrap with active Child Profiles routes to the Guardian dashboard.
- Test `UNAUTHENTICATED` maps to auth recovery.
- Test `DEVICE_REVOKED` maps to a blocked-device route or state and does not keep retrying automatically.
- Test `DEVICE_LIMIT_REACHED` maps to a device-limit route or state and does not keep retrying automatically.
- Test retryable network failure maps to a retryable error state.
- Test raw backend error details are not exposed as UI-facing messages.
- Test device label building for normal brand/model, blank values, unknown values, and duplicated brand/model values.
- Test timezone provider returns an IANA timezone string when available and gracefully handles unavailable values.
- Test app build metadata includes the configured app version/build fields expected by the backend contract.
- Use existing Android local test conventions for pure coordinator and value-builder tests.
- Add instrumented or Compose tests only where routing behavior cannot be validated through a plain JVM seam.

## Out of Scope

- Backend schema or Convex mutation changes.
- Clerk signup/sign-in UI design beyond the minimum needed to observe auth readiness if that surface already exists.
- Guardian dashboard implementation.
- Child Profile creation flow implementation.
- Enrollment Code generation.
- Child Device enrollment.
- FCM token registration.
- Guardian Device management screens.
- Guardian Device revocation UI.
- Pairing additional Guardian Devices through Guardian Pairing Codes.
- Reactivating revoked Guardian Devices.
- Child Mode authentication or Child Device Credential handling.
- Room database architecture beyond the smallest local persistence needed for this slice.
- Production release hardening of analytics or observability.

## Further Notes

- This PRD follows the existing domain language: Guardian, Guardian Account, Guardian Device, Household, Child Profile, Guardian Mode, Child Mode, and Cereveil App.
- This PRD follows ADR-0031 by keeping Guardian Account identity separate from Child Device identity.
- This PRD follows ADR-0041 by keeping Guardian Mode integration separate from Child Mode and using shared core modules only for appropriate non-role-specific pieces.
- This PRD follows ADR-0043 by treating Guardian local state as cached/routing state rather than backend authority.
- This PRD follows ADR-0051 by making Android call backend bootstrap after Clerk authentication and by keying Guardian Device records through a locally persisted app-generated installation ID.
- The previous Guardian Auth Bootstrap Backend PRD remains the source of truth for backend lifecycle rules, safe typed errors, and response shape.
