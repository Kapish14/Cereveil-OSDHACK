Status: ready-for-agent

# Issue and Refresh Child Device JWTs with Backend Challenges

## Parent

.scratch/child-device-enrollment/PRD.md

## What to build

Build the Device Identity token challenge and issuance path for enrolled Child Devices. Child Mode should request a backend-issued one-use challenge for its Child Device Credential, sign it with the Android Keystore private key, and receive a new fifteen-minute Child Device JWT only when the credential and Active Enrollment are still valid.

This slice replaces reusable bearer refresh tokens with proof-of-possession refresh.

## Acceptance criteria

- [ ] No reusable bearer refresh token is issued or stored for Child Devices.
- [ ] Device Identity exposes a challenge creation endpoint for Child Device Credentials.
- [ ] Token challenges are backend-issued, short-lived, one-use, and tied to a Child Device Credential.
- [ ] Child Mode signs challenges with its Android Keystore private key.
- [ ] Device Identity verifies challenge status, credential status, Active Enrollment status, and signature before issuing a JWT.
- [ ] Issued Child Device JWTs live for fifteen minutes.
- [ ] Child Device JWTs include Child Device Credential, Active Enrollment, and Child Device identity claims.
- [ ] JWT claims are used to resolve a ChildDeviceActor without trusting request arguments for identity.
- [ ] Sensitive child-side backend operations resolve current credential and Active Enrollment state instead of relying only on JWT expiry.
- [ ] Revoked credentials cannot receive new JWTs.
- [ ] Revoked or ended Active Enrollments cannot receive new JWTs.
- [ ] Reused, expired, or credential-mismatched challenges fail safely.
- [ ] Android Child token provider can request, sign, and exchange challenges through fakeable seams.
- [ ] Tests cover challenge creation, one-use behavior, expiry behavior, valid refresh, invalid signature, revoked credential, revoked/ended enrollment, JWT lifetime, claims, and Android token-provider behavior with fakes.

## Blocked by

- .scratch/child-device-enrollment/issues/04-complete-enrollment-with-keystore-proof.md
