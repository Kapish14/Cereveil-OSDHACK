Status: ready-for-agent

# Keep FCM Registration Separate from Enrollment

## Parent

.scratch/child-device-enrollment/PRD.md

## What to build

Add or wire the separate authenticated Child Device path for FCM token registration after enrollment. Enrollment completion should not fail because push-token availability is delayed or unavailable, and FCM tokens should remain delivery endpoints rather than device identity.

## Acceptance criteria

- [ ] Enrollment completion does not accept or store a Child Device FCM token.
- [ ] Child Mode can register or update its FCM token after enrollment through an authenticated Child Device call.
- [ ] FCM token registration authorizes the caller as the active Child Device for the Active Enrollment.
- [ ] FCM tokens are stored separately from Child Device identity and Child Device Credentials.
- [ ] Token rotation updates delivery state without changing Child Device identity or Active Enrollment.
- [ ] Registration failure does not undo enrollment or local enrollment state.
- [ ] Tests cover no FCM handling in completion, successful post-enrollment token registration, authorization failures, token rotation, and registration failure isolation.

## Blocked by

- .scratch/child-device-enrollment/issues/04-complete-enrollment-with-keystore-proof.md
