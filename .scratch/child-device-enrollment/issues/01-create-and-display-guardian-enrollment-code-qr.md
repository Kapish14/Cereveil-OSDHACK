Status: ready-for-agent

# Create and Display Guardian Enrollment Code QR

## Parent

.scratch/child-device-enrollment/PRD.md

## What to build

Build the Guardian Mode path for creating, displaying, regenerating, and cancelling a QR-only Enrollment Code for one Unenrolled Child Profile. An authenticated Guardian should be able to start Child Device setup from an Unenrolled Child Profile, receive a five-minute versioned QR payload, see expiry state, regenerate a code, cancel a code, and get safe errors when the Child Profile or Guardian lifecycle state does not allow enrollment.

This slice should establish the Enrollment Code backend model and Guardian-facing API/UI contract, but it should not implement Child Mode preview or completion.

## Acceptance criteria

- [ ] Enrollment Codes are stored in a separate backend table rather than on Child Profiles.
- [ ] Enrollment Code rows store only a hash of the raw code.
- [ ] Raw Enrollment Codes are returned only at creation time for QR display and are not logged.
- [ ] Enrollment Codes are generated as 128-bit random base64url tokens without padding.
- [ ] Guardian Mode displays a versioned QR JSON payload with a Cereveil child enrollment type marker and the opaque code.
- [ ] The QR payload does not include Child Profile ID, Household ID, Guardian email, policy details, enrollment IDs, or credential IDs.
- [ ] Enrollment Codes expire five minutes after creation.
- [ ] At most one active unexpired Enrollment Code exists per Child Profile.
- [ ] Creating or regenerating a code revokes previous active codes for the same Child Profile.
- [ ] Guardian Mode can manually cancel an active Enrollment Code.
- [ ] Leaving the Guardian QR screen does not revoke the Enrollment Code.
- [ ] Enrollment Code creation and cancellation require an authenticated Guardian and derive Guardian Account, Guardian Device when available, and Household server-side.
- [ ] Enrollment Code creation accepts only the Child Profile target and never trusts client-supplied ownership IDs.
- [ ] Enrollment Code creation is allowed only for active Unenrolled Child Profiles in the Guardian's Household.
- [ ] Enrollment Code creation is rejected for already enrolled Child Profiles, deleting Child Profiles, deleting Households, disabled Guardian Accounts, and deleting Guardian Accounts.
- [ ] Guardian UI exposes QR display, expiry/countdown state, regenerate, cancel, and safe error states.
- [ ] Tests cover successful create, hash-only storage, QR payload shape, five-minute expiry, regenerate revocation, cancel, authorization, lifecycle rejections, and Guardian UI state with fakes.

## Blocked by

None - can start immediately
