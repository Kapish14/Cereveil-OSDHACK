Status: ready-for-agent

# Migrate Supervision Heartbeat to the Authenticated Child Wrapper

## Parent

.scratch/authenticated-function-wrapper-hardening/PRD.md

## What to build

Migrate the normal authenticated Supervision Heartbeat path to `childDeviceHttpAction`. A currently authorized Child Device must submit validated capability availability through the common HTTP contract, have its complete ChildDeviceActor revalidated in the heartbeat transaction, and update Supervision Health without exposing capability data in logs. Child Mode must consume the common typed error and bounded-recovery behavior while preserving existing Pending Supervision Health, Online, Fully Protected, Protection Degraded, and offline scheduling semantics.

## Acceptance criteria

- [ ] The heartbeat route uses `childDeviceHttpAction` rather than endpoint-specific JWT authentication or raw response construction.
- [ ] The endpoint callback accepts only the validated ChildDeviceActor from the wrapper and validated heartbeat input.
- [ ] Capability input requires the complete expected Boolean shape; malformed input returns `400 VALIDATION_FAILED`.
- [ ] The internal heartbeat mutation accepts nested actor and input objects and transactionally revalidates the complete Child authorization chain.
- [ ] Revocation or lifecycle change between boundary resolution and the heartbeat transaction returns `401 CHILD_DEVICE_UNAUTHORIZED` and performs no health update.
- [ ] A valid first heartbeat transitions Pending Supervision Health to Online and the appropriate Fully Protected or Protection Degraded state.
- [ ] Later valid heartbeats update the latest capability and liveness state and retain compatible offline scheduling behavior.
- [ ] Missing expected Supervision Health state for an otherwise valid actor maps to `500 INTERNAL_ERROR`, not authentication failure.
- [ ] Success and error responses use the shared JSON contract and include a server-generated `X-Request-Id`.
- [ ] Successful heartbeat receipt and all failures use the typed allowlisted logger.
- [ ] Heartbeat logs do not include capability names or values, request bodies, actor/database IDs, JWT material, or raw errors.
- [ ] Child Mode uses the shared typed validation, unauthorized, internal, and network error behavior, including at most one credential refresh and heartbeat retry.
- [ ] Tests verify successful first and later heartbeats, malformed input, transactional revocation rejection, safe missing-state failure, response correlation, logging privacy, and preservation of health/offline behavior through the public route seam.

## Blocked by

- .scratch/authenticated-function-wrapper-hardening/issues/02-introduce-child-device-http-action-through-policy.md
