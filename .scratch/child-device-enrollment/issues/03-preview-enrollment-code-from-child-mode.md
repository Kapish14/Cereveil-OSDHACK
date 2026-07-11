Status: ready-for-agent

# Preview Enrollment Code from Child Mode

## Parent

.scratch/child-device-enrollment/PRD.md

## What to build

Build the read-only Child Mode Enrollment Preview path. After local Protection Setup, Child Mode should scan a QR payload, reject malformed or unrelated QR codes locally where possible, call the Device Identity preview endpoint for valid Cereveil enrollment payloads, and show only sanitized setup details before completion.

Preview must not consume the Enrollment Code, reserve the Child Profile, create Child Device identity, or require later completion to prove that preview happened.

## Acceptance criteria

- [ ] Child Mode exposes QR scanning only after local Protection Setup has completed.
- [ ] Child Mode parses the versioned Cereveil child enrollment QR payload.
- [ ] Child Mode rejects malformed, unsupported-version, and unrelated QR payloads before completion.
- [ ] Enrollment Preview is implemented as a Device Identity HTTP endpoint.
- [ ] Enrollment Preview validates the incoming code by hash lookup without exposing raw code storage.
- [ ] Enrollment Preview returns only child display name, code expiration, and server time for valid active codes.
- [ ] Enrollment Preview does not return Child Profile ID, Household ID, Guardian email, policy details, enrollment IDs, or credential IDs.
- [ ] Enrollment Preview does not consume the Enrollment Code.
- [ ] Enrollment Preview does not create Child Device, Active Enrollment, Child Device Credential, Policy Application State, or Supervision Health rows.
- [ ] Enrollment Preview uses generic invalid-code errors for nonexistent, invalid, expired, revoked, and consumed codes.
- [ ] Completion remains possible without a prior preview call.
- [ ] Android Child UI maps preview success, invalid code, malformed QR, and network unavailable into stable app states.
- [ ] Tests cover valid preview, sanitized response, no consumption, no identity row creation, generic invalid-code handling, malformed QR handling, scan routing after Protection Setup, and Child UI state with fakes.

## Blocked by

- .scratch/child-device-enrollment/issues/01-create-and-display-guardian-enrollment-code-qr.md
