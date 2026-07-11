Status: ready-for-agent

# Migrate Child Push-Token Registration to the Authenticated Child Wrapper

## Parent

.scratch/authenticated-function-wrapper-hardening/PRD.md

## What to build

Migrate authenticated Child Device FCM token registration and rotation to `childDeviceHttpAction`. The common boundary must authorize the current ChildDeviceActor, validate token input, and revalidate the complete actor chain in the registration transaction before creating or updating delivery state. Preserve the separation between Child Device identity and FCM delivery tokens, keep enrollment independent of token availability, and ensure neither token values nor correlating identifiers enter logs or safe errors.

## Acceptance criteria

- [ ] The child push-token route uses `childDeviceHttpAction` rather than endpoint-specific JWT authentication or raw response construction.
- [ ] The endpoint callback accepts only the wrapper-resolved ChildDeviceActor and validated push-token input.
- [ ] Missing, empty, malformed, or oversized token input returns `400 VALIDATION_FAILED`.
- [ ] The push token is hashed and encrypted before persistence and remains separate from Child Device identity and Child Device Credential records.
- [ ] The internal registration mutation accepts nested actor and input objects and transactionally revalidates the complete Child authorization chain.
- [ ] Revocation or lifecycle change between boundary resolution and registration returns `401 CHILD_DEVICE_UNAUTHORIZED` and performs no delivery-state write.
- [ ] First registration creates active delivery state for the authorized Child Device and Active Enrollment.
- [ ] Token rotation updates delivery state without changing Child Device identity, Active Enrollment, or credential identity.
- [ ] Encryption, configuration, or unexpected persistence failure returns `500 INTERNAL_ERROR` without exposing token or implementation details.
- [ ] Registration failure does not undo enrollment, Role Lock, local enrollment state, policy state, or Supervision Health.
- [ ] Success and error responses use the shared JSON contract and include a server-generated `X-Request-Id`.
- [ ] Successful registration/rotation and all failures use the typed allowlisted logger.
- [ ] Logs do not include raw or encrypted tokens, token hashes, request bodies, actor/database IDs, JWT material, or raw errors.
- [ ] Child Mode uses the shared typed validation, unauthorized, internal, and network error behavior, including at most one credential refresh and registration retry.
- [ ] Tests verify first registration, rotation, malformed input, transactional revocation rejection, safe crypto/configuration failure, failure isolation, correlation headers, and log privacy through the public route seam.

## Blocked by

- .scratch/authenticated-function-wrapper-hardening/issues/02-introduce-child-device-http-action-through-policy.md
