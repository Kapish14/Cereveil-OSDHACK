Status: ready-for-agent

# Handle retryable bootstrap failures

## What to build

Add retryable failure behavior for temporary bootstrap problems such as network or service interruptions. The app should show a retryable bootstrap error state, allow an explicit bounded retry, and keep permanent auth/device lifecycle errors out of the retry path.

## Acceptance criteria

- [ ] Retryable network or service failures map to a retryable bootstrap error state.
- [ ] The Guardian can trigger an explicit retry from the retryable error state.
- [ ] Retry behavior is bounded and does not run an uncontrolled automatic retry loop.
- [ ] Retrying reuses the persisted `guardianInstallationId` and current app/device metadata.
- [ ] Permanent errors such as `UNAUTHENTICATED`, `DEVICE_REVOKED`, and `DEVICE_LIMIT_REACHED` are not treated as retryable network failures.
- [ ] Tests cover retryable failure, explicit retry success, and permanent error non-retry behavior through the coordinator or view-model seam.

## Blocked by

- .scratch/guardian-auth-android-integration/issues/02-bootstrap-after-clerk-auth-readiness.md
- .scratch/guardian-auth-android-integration/issues/03-route-guardian-startup-from-bootstrap-state.md
- .scratch/guardian-auth-android-integration/issues/04-handle-bootstrap-auth-and-device-lifecycle-failures.md
