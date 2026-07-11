Status: ready-for-agent

# Bootstrap after Clerk auth readiness

## What to build

Connect Guardian Mode authentication readiness to the backend Guardian auth bootstrap mutation. After Clerk reports an authenticated Guardian session, the app should call `api.modules.guardianAuth.public.bootstrapGuardian` through a small Guardian auth client, send only the approved local app/device facts, and persist the returned Guardian bootstrap state for startup routing and later Guardian Mode features.

## Acceptance criteria

- [ ] Bootstrap waits until the Guardian flavor has an authenticated Clerk-ready state.
- [ ] Bootstrap calls the Convex mutation for `api.modules.guardianAuth.public.bootstrapGuardian`.
- [ ] The request sends only `guardianInstallationId`, optional `deviceLabel`, app build metadata, and optional timezone.
- [ ] The request does not send Clerk user ID, email, Guardian Account ID, Household ID, or Guardian Device ID as authority.
- [ ] A successful response persists Guardian Account ID, Household ID, Guardian Device ID, Guardian Device status, active Child Profile presence, and server time when returned.
- [ ] The Convex call is hidden behind a Guardian auth client abstraction so startup orchestration can be tested without the real backend.
- [ ] The orchestration is exposed through a Guardian bootstrap coordinator or view model.
- [ ] Coordinator tests use fakes for auth readiness, Guardian auth client, local state, and metadata providers.

## Blocked by

- .scratch/guardian-auth-android-integration/issues/01-persist-guardian-installation-identity-and-metadata.md
