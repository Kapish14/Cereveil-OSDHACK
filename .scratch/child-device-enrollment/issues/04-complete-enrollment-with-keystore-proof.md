Status: ready-for-agent

# Complete Enrollment with Keystore Proof

## Parent

.scratch/child-device-enrollment/PRD.md

## What to build

Build the core Child Device enrollment completion path. Child Mode should generate non-exportable Android Keystore key material, prove possession during Device Identity enrollment completion, and persist the returned local enrollment state immediately after backend success. The backend should consume the Enrollment Code once and atomically create the Child Device, Active Enrollment, Child Device Credential, desired Policy Application State, Pending Supervision Health, and first Child Device JWT.

This slice owns the moment a Prepared Child Device becomes an actively enrolled Child Device, but it should not deliver the full Supervision Policy body, register FCM tokens, or implement token refresh.

## Acceptance criteria

- [ ] Enrollment completion is implemented as a Device Identity HTTP endpoint.
- [ ] Completion accepts the Enrollment Code, generated public key material, proof-of-possession, and required app/device metadata.
- [ ] Completion does not accept or trust client-supplied Household ID or Child Profile authority.
- [ ] Completion derives Household and Child Profile from the validated Enrollment Code.
- [ ] Completion verifies the Enrollment Code is active, unexpired, unconsumed, and belongs to an Unenrolled Child Profile.
- [ ] Completion verifies that the Child Profile has no Active Enrollment.
- [ ] Completion verifies proof-of-possession for the generated Android Keystore key.
- [ ] V1 completion does not require Android hardware-backed attestation.
- [ ] Successful completion consumes the Enrollment Code.
- [ ] Preview, malformed scan, invalid proof, and failed completion attempts do not consume the Enrollment Code.
- [ ] Completion is strict and non-resumable by Enrollment Code in v1.
- [ ] Completion does not accept a client idempotency key in v1.
- [ ] Reusing a consumed code fails safely.
- [ ] Using a stale code after the Child Profile already has an Active Enrollment fails safely.
- [ ] Successful completion atomically creates an active Child Device row.
- [ ] Backend Child Device status does not include `prepared`.
- [ ] Successful completion creates an Active Enrollment with Role Lock active.
- [ ] Active Enrollment references the Enrollment Code row that created it.
- [ ] Successful completion creates one active Child Device Credential for the Active Enrollment.
- [ ] Active Enrollment and Child Device Credential remain separate records.
- [ ] Successful completion creates desired Policy Application State with the current desired policy version and no applied policy version.
- [ ] Successful completion creates Pending Supervision Health.
- [ ] Completion does not include a protection capability snapshot.
- [ ] Completion does not register FCM tokens.
- [ ] Completion does not create an initial fetch-policy Child Device command.
- [ ] Completion issues the first fifteen-minute Child Device JWT.
- [ ] Completion response includes only Child Device ID, Active Enrollment ID, credential ID, child display name, desired policy version, access JWT, access JWT expiry, enrolled/server time, and backend environment when needed.
- [ ] Completion response omits Household ID and the full Supervision Policy body.
- [ ] Child Mode stores credential ID, Active Enrollment ID, Child Device ID, child display name, desired policy version, JWT, JWT expiry, Role Lock state, enrolled time, backend environment when needed, and Keystore key alias/reference.
- [ ] Child Mode does not store the Enrollment Code, raw QR payload, Household ID, or private key in local enrollment state.
- [ ] Child Mode persists local enrollment state immediately after backend success and before policy fetch.
- [ ] Android maps invalid code, already enrolled, generic enrollment failure, and network unavailable into stable app states.
- [ ] Tests cover successful completion, atomic row creation, strict consumed-code behavior, stale-code rejection, invalid proof without consumption, no hardware attestation requirement, response shape, local persistence order, and Android error mapping with fakes.

## Blocked by

- .scratch/child-device-enrollment/issues/01-create-and-display-guardian-enrollment-code-qr.md
- .scratch/child-device-enrollment/issues/03-preview-enrollment-code-from-child-mode.md
