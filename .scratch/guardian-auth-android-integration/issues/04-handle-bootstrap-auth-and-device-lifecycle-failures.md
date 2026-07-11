Status: ready-for-agent

# Handle bootstrap auth and device lifecycle failures

## What to build

Map permanent Guardian auth bootstrap failures into stable app states and recovery paths. The app should handle `UNAUTHENTICATED`, `DEVICE_REVOKED`, and `DEVICE_LIMIT_REACHED` without exposing raw backend payloads or repeatedly retrying failures that require Guardian action.

## Acceptance criteria

- [ ] `UNAUTHENTICATED` maps to an auth recovery route or state.
- [ ] `DEVICE_REVOKED` maps to a blocked Guardian Device route or state.
- [ ] `DEVICE_LIMIT_REACHED` maps to a device-limit route or state.
- [ ] Permanent device lifecycle failures do not trigger uncontrolled automatic bootstrap retries.
- [ ] UI-facing error state does not expose raw backend exception payloads or internal identifiers.
- [ ] Error mapping is represented by a Guardian bootstrap error model rather than raw transport exceptions.
- [ ] Tests cover each permanent error and verify the emitted state and retry behavior.

## Blocked by

- .scratch/guardian-auth-android-integration/issues/02-bootstrap-after-clerk-auth-readiness.md
