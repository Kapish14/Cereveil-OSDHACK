Status: ready-for-agent

# Child Device Enrollment PRD

## Problem Statement

Guardian Mode can create an Unenrolled Child Profile with an Initial Supervision Policy, but Cereveil does not yet have the enrollment path that binds a real Child Device to that profile. Without this slice, a Guardian can prepare the Child identity and baseline policy but cannot generate a QR code, the Child APK cannot scan and validate the code, no Child Device Credential exists, Child Mode cannot obtain a Child Device JWT, and the Guardian dashboard cannot move the Child from unenrolled setup into an actively enrolled state.

## Solution

Build the Child Device Enrollment slice. Guardian Mode will generate a five-minute, QR-only, single-use Enrollment Code for one Unenrolled Child Profile and display a versioned QR payload. Child Mode will complete Protection Setup locally before scanning, optionally perform a read-only Enrollment Preview, generate non-exportable Android Keystore key material, and call the Device Identity enrollment completion endpoint. Convex will validate the code, verify proof-of-possession for the generated key, atomically create the Child Device, Active Enrollment, Child Device Credential, desired Policy Application State, and Pending Supervision Health, consume the Enrollment Code, and issue a fifteen-minute Child Device JWT. Child Mode will then persist local enrollment state, fetch and store the current Supervision Policy through the normal authenticated child policy API, acknowledge policy application, and report health through the first heartbeat.

## User Stories

1. As a Guardian, I want to generate an Enrollment Code for an Unenrolled Child Profile, so that I can bind a Child Device to the correct Child.
2. As a Guardian, I want the Enrollment Code to be shown as a QR code, so that the Child Device can scan it without manual entry.
3. As a Guardian, I want Enrollment Codes to expire after five minutes, so that stale QR codes do not remain usable for long.
4. As a Guardian, I want only one active Enrollment Code per Child Profile, so that old QR codes stop working when I regenerate a new one.
5. As a Guardian, I want to cancel an active Enrollment Code, so that I can invalidate a QR code if I displayed the wrong Child or changed my mind.
6. As a Guardian, I want leaving the QR screen not to revoke the Enrollment Code, so that accidental navigation or backgrounding does not interrupt same-room setup.
7. As a Guardian, I want Guardian Mode to observe enrollment state while showing the QR code, so that the UI moves forward when the Child Device enrolls.
8. As a Guardian, I want stale QR screens to stop working after enrollment completes, so that one Child Profile cannot be enrolled twice.
9. As a Guardian, I want Enrollment Code creation to be rejected for already enrolled Child Profiles, so that one Child Profile keeps only one active Child Device.
10. As a Guardian, I want Enrollment Code creation to be rejected for deleting Child Profiles, deleting Households, and disabled or deleting Guardian Accounts, so that enrollment cannot bypass lifecycle rules.
11. As a Guardian, I want the dashboard to show the Child as enrolled after backend enrollment succeeds, so that I know the phone has been paired.
12. As a Guardian, I want the dashboard to distinguish enrolled from policy applied and protection healthy, so that it does not overstate the Child Device's readiness.
13. As a Guardian, I want pending policy and Pending Supervision Health states after enrollment, so that I understand the Child Device still needs to acknowledge policy and report capabilities.
14. As a Guardian, I want a clear regenerate path when a code expires, so that setup can continue without recreating the Child Profile.
15. As a Guardian, I want safe errors when enrollment fails, so that raw backend details or internal identifiers are not shown.
16. As a Child using the Child Device, I want scanning to happen only after Protection Setup, so that enrollment does not begin before required Android capability setup.
17. As a Child using the Child Device, I want to preview sanitized setup details after scanning, so that the device can confirm the intended Child before enrollment completes.
18. As a Child using the Child Device, I want Enrollment Preview to be read-only, so that a scan does not accidentally consume the Enrollment Code.
19. As a Child using the Child Device, I want unrelated or malformed QR codes to be rejected cleanly, so that scanner errors are understandable.
20. As a Child using the Child Device, I want the QR payload to avoid exposing Child Profile IDs, Household IDs, Guardian emails, or policy details, so that QR screenshots do not leak unnecessary family data.
21. As a Child using the Child Device, I want enrollment to use Android Keystore key material, so that ongoing device authorization is tied to non-exportable private key material.
22. As a Child using the Child Device, I want v1 enrollment not to require hardware attestation, so that device compatibility and Play Integrity complexity do not block the core enrollment path.
23. As a Child using the Child Device, I want successful enrollment to immediately Role Lock the app into Child Mode, so that the installation cannot casually switch roles after pairing.
24. As a Child using the Child Device, I want local enrollment state to be persisted immediately after backend completion, so that app restarts can resume as an enrolled Child Device.
25. As a Child using the Child Device, I want policy-controlled monitoring to start only after the current Supervision Policy is fetched, stored, and acknowledged, so that local enforcement follows backend policy rather than inferred defaults.
26. As a Child using the Child Device, I want Child Device JWT refresh to use Keystore challenge signing, so that the device does not store a reusable bearer refresh token.
27. As a Child using the Child Device, I want first heartbeat to establish Supervision Health, so that protection capability state comes from the normal heartbeat path.
28. As a Child using the Child Device, I want FCM token registration to be separate from enrollment, so that push-token availability does not block pairing.
29. As a developer, I want Enrollment Codes stored as hashes, so that raw bearer bootstrap secrets are not retained in Convex rows.
30. As a developer, I want Enrollment Codes to use 128-bit random base64url tokens, so that QR-only codes remain effectively unguessable without v1 rate limiting.
31. As a developer, I want QR payloads to be versioned JSON with a Cereveil enrollment type marker, so that Child Mode can reject unrelated QR codes and the protocol can evolve.
32. As a developer, I want Guardian Enrollment Code creation and cancellation to be normal authenticated Guardian Convex mutations, so that Guardian authority uses existing Guardian Account and Household authorization.
33. As a developer, I want Child Enrollment Preview and completion to be Device Identity HTTP endpoints, so that pre-auth Child Mode traffic stays in the Device Identity boundary.
34. As a developer, I want Enrollment Preview to return only child display name, code expiry, and server time, so that preview stays sanitized and non-authoritative.
35. As a developer, I want enrollment completion to be strict and non-resumable by Enrollment Code, so that a copied or retried code cannot create ambiguous recovery semantics.
36. As a developer, I want a valid Enrollment Code to be consumed only by successful completion, so that failed preview, invalid proof, and network failures before backend success do not burn the code.
37. As a developer, I want completion to fail after the code is consumed, so that a backend-success/local-persistence-failure case is handled by Guardian recovery instead of a two-phase protocol.
38. As a developer, I want completion to reject Child Profiles that already have an Active Enrollment, so that the one-active-device rule is enforced server-side.
39. As a developer, I want completion to create Child Device, Active Enrollment, Child Device Credential, desired Policy Application State, and Pending Supervision Health atomically, so that backend enrollment state cannot be partially created.
40. As a developer, I want Active Enrollment to reference the Enrollment Code that created it, so that bootstrap traceability exists without exposing the raw code.
41. As a developer, I want Child Device records to start as active backend rows only after successful enrollment, so that Prepared Child Device remains local-only.
42. As a developer, I want Child Device Credential records to remain separate from Active Enrollment, so that credential revocation and future rotation do not rewrite the enrollment relationship model.
43. As a developer, I want v1 to allow exactly one active Child Device Credential per Active Enrollment, so that behavior stays simple while preserving the credential lifecycle boundary.
44. As a developer, I want the first Child Device Credential to become active immediately after enrollment completion, so that the backend can issue the first Child Device JWT.
45. As a developer, I want Child Device JWTs to carry Child Device Credential, Active Enrollment, and Child Device identity claims, so that child APIs can resolve a ChildDeviceActor without trusting request arguments for identity.
46. As a developer, I want Child Device JWTs to live for fifteen minutes, so that bearer-token exposure is bounded without excessive refresh traffic.
47. As a developer, I want token issuance and refresh to use backend-issued one-use challenges, so that replay protection is server-controlled.
48. As a developer, I want child-side API authorization to verify current credential and Active Enrollment state, so that revocation takes effect even before a JWT naturally expires for sensitive operations.
49. As a developer, I want the Child APK to store credential ID and Active Enrollment ID but not Household ID, so that local state contains useful routing identifiers without leaking unnecessary ownership scope.
50. As a developer, I want the enrollment response to omit the full Supervision Policy body, so that policy delivery uses the normal authenticated child policy path.
51. As a developer, I want enrollment completion to omit protection capability snapshots, so that Supervision Health is initialized by heartbeat rather than an unauthenticated or special enrollment-time claim.
52. As a developer, I want initial policy fetch to happen directly after local enrollment persistence, so that no initial Child Device command is needed.
53. As a developer, I want FCM registration to be a separate authenticated child-device call, so that delivery endpoints remain separate from identity.
54. As a developer, I want invalid, expired, revoked, consumed, and nonexistent code preview failures to use generic invalid-code errors, so that the endpoint does not become a code existence oracle.
55. As a developer, I want no coarse rate limiting in v1, so that implementation stays focused while relying on high-entropy QR-only codes, five-minute expiry, generic errors, and no raw-code logging.
56. As a developer, I want safe app-facing failure buckets, so that Android can handle invalid code, already enrolled, generic enrollment failure, and network unavailable states without raw backend exceptions.

## Implementation Decisions

- Build a focused Child Device Enrollment slice after Guardian Child Profile onboarding.
- Use the existing domain language: Guardian, Guardian Account, Guardian Device, Household, Child Profile, Unenrolled Child Profile, Prepared Child Device, Enrollment Code, Enrollment Preview, Child Device, Active Enrollment, Child Device Credential, Child Device JWT, Policy Application State, Pending Supervision Health, Role Lock, Supervision Policy, and Supervision Health.
- Preserve the existing split between Active Enrollment and Child Device Credential.
- Treat Prepared Child Device as local Child Mode state only.
- Backend Child Device rows are created only when enrollment succeeds.
- Backend Child Device status should not include `prepared`; backend Child Device status starts at active and can later be revoked.
- Add a separate Enrollment Codes table rather than storing current code fields on Child Profiles.
- Enrollment Code rows include Household, Child Profile, hashed code, lifecycle status, expiry, create/revoke/consume timestamps, creating Guardian Account, optionally creating Guardian Device, optionally revoking Guardian Account, optionally revoking Guardian Device, and consumed enrollment traceability.
- Store only a hash of the Enrollment Code.
- Do not log raw Enrollment Codes.
- Generate Enrollment Codes as 128-bit random base64url tokens without padding.
- Present Enrollment Codes only as QR codes in v1.
- Do not add a human-typeable fallback in v1.
- Use a versioned QR JSON payload with a Cereveil child enrollment type marker and the opaque code.
- Do not include Child Profile ID, Household ID, Guardian email, policy details, enrollment IDs, or credential IDs in the QR payload.
- Enrollment Codes expire five minutes after creation.
- Maintain at most one active unexpired Enrollment Code per Child Profile.
- Creating or regenerating an Enrollment Code revokes previous active codes for the same Child Profile.
- Guardian Mode can manually cancel active Enrollment Codes.
- Leaving the Guardian QR screen does not revoke the Enrollment Code.
- Successful enrollment consumes the Enrollment Code.
- Preview, malformed scan, invalid proof, and failed completion attempts do not consume the Enrollment Code.
- Completion is strict and non-resumable by Enrollment Code in v1.
- Do not add client idempotency keys to enrollment completion in v1.
- Reusing a consumed code fails.
- Using a stale code after the Child Profile already has an Active Enrollment fails.
- Guardian Enrollment Code creation and cancellation are authenticated Guardian Convex mutations.
- Guardian code creation derives Guardian Account, Guardian Device when available, and Household server-side.
- Guardian code creation accepts only the Child Profile target and never trusts client-supplied ownership IDs.
- Guardian code creation is allowed only for active Unenrolled Child Profiles in the Guardian's Household.
- Guardian code creation is rejected for already enrolled Child Profiles, deleting Child Profiles, deleting Households, disabled Guardian Accounts, and deleting Guardian Accounts.
- Guardian Mode displays QR payload and expiry.
- Guardian Mode observes enrollment state through Convex and auto-transitions away from the QR view when enrollment completes.
- Child Enrollment Preview is a Device Identity HTTP endpoint.
- Child Enrollment completion is a Device Identity HTTP endpoint.
- Keep all pre-auth Child Mode enrollment traffic in the Device Identity HTTP surface.
- Device Identity HTTP handlers perform request parsing, validation, generic invalid-code handling, proof verification, JWT issuance, and safe logging.
- Device Identity HTTP handlers delegate atomic database writes to internal Convex mutations where needed.
- Enrollment Preview is read-only and non-authoritative.
- Enrollment Preview is a normal UX step but not a backend prerequisite for completion.
- Enrollment Preview returns only child display name, code expiration, and server time.
- Enrollment Preview returns generic invalid-code errors for nonexistent, invalid, expired, revoked, or consumed codes.
- Child Mode UI exposes scanning only after local Protection Setup has completed.
- Backend does not try to prove Protection Setup completion during preview.
- Completion accepts the Enrollment Code, generated public key material, proof-of-possession, app/device metadata needed for a Child Device record, and no client-supplied Household or Child Profile authority.
- Completion derives Household and Child Profile from the validated Enrollment Code.
- Completion verifies that the Child Profile has no Active Enrollment.
- Completion generates and records a Child Device row.
- Completion creates an Active Enrollment with Role Lock active.
- Active Enrollment references the Enrollment Code row that created it.
- Completion creates one active Child Device Credential for the Active Enrollment.
- V1 Child Device Credential uses a non-exportable Android Keystore key and proof-of-possession.
- V1 does not require Android hardware-backed attestation.
- Completion creates desired Policy Application State with the current desired policy version.
- Completion does not mark the policy as applied.
- Completion creates Pending Supervision Health.
- Completion does not include an initial capability snapshot.
- Completion does not register FCM tokens.
- Completion does not create an initial Child Device command to fetch policy.
- Completion issues the first fifteen-minute Child Device JWT.
- Completion response includes only local enrollment/auth state needed by Child Mode: Child Device ID, Active Enrollment ID, credential ID, Child display name, desired policy version, access JWT, access JWT expiry, enrolled timestamp or server time, and backend environment when needed.
- Completion response does not include Household ID.
- Completion response does not include the full Supervision Policy body.
- Child Mode stores credential ID for auth and Active Enrollment ID for enrollment-scoped local state and request routing.
- Child Mode stores the Child Device ID, child display name, desired policy version, JWT, JWT expiry, Role Lock state, enrolled timestamp, backend environment when needed, and Android Keystore key alias/reference.
- Child Mode does not store the Enrollment Code or raw QR payload after enrollment.
- Child Mode does not store the private key; Android Keystore holds the private key material.
- Child Mode stores the Supervision Policy separately as the last accepted policy.
- After local enrollment state is persisted, Child Mode fetches current policy through the normal authenticated child policy API.
- Policy-controlled monitoring and collection start only after Child Mode fetches, stores, and acknowledges the policy.
- Child Mode acknowledges applied policy through the normal policy acknowledgement path.
- First authenticated heartbeat initializes or updates Supervision Health with actual capability state.
- FCM token registration happens through a later separate authenticated child-device call.
- Child Device JWTs include Child Device Credential, Active Enrollment, and Child Device identity claims.
- Child Device JWTs do not replace backend authorization checks; backend resolves current credential and Active Enrollment state.
- Do not issue reusable bearer refresh tokens for Child Devices.
- Token issuance and refresh use backend-issued, short-lived, one-use challenges tied to Child Device Credentials.
- Child Mode signs token challenges with the Android Keystore private key.
- Device Identity verifies challenge status, credential status, Active Enrollment status, and signature before issuing a new fifteen-minute Child Device JWT.
- App-facing errors should map to stable buckets: invalid or expired code, child already enrolled, generic enrollment failed, and network unavailable.
- No coarse rate limiting is required in v1, but code entropy, expiry, hashing, generic invalid errors, and no raw-code logging are required.

## Testing Decisions

- Test external behavior at the highest practical seams rather than private helper decomposition.
- The preferred backend test seam is the public Guardian Convex mutation boundary for code creation/cancellation and the Device Identity HTTP boundary for preview, completion, and token challenge issuance.
- Internal helper tests should be added only when crypto, hashing, token signing, or payload parsing cannot be exercised clearly through the public boundary.
- Backend tests should verify Guardian code creation derives Guardian Account, Guardian Device when available, and Household server-side.
- Backend tests should verify Guardian code creation rejects unauthenticated callers and lifecycle-invalid Guardian Accounts, Households, and Child Profiles.
- Backend tests should verify code creation requires an Unenrolled Child Profile and rejects already enrolled profiles.
- Backend tests should verify creating a new code revokes previous active codes for the same Child Profile.
- Backend tests should verify Enrollment Codes expire after five minutes.
- Backend tests should verify only code hashes are stored and raw codes are not persisted.
- Backend tests should verify QR payload shape is versioned and contains only the opaque code plus protocol marker/version.
- Backend tests should verify cancel revokes active codes and does not affect completed enrollments.
- Backend tests should verify preview returns only sanitized setup details for valid codes.
- Backend tests should verify preview does not consume codes or create identity rows.
- Backend tests should verify preview uses generic invalid-code errors for invalid, expired, revoked, consumed, and nonexistent codes.
- Backend tests should verify completion succeeds once for a valid unused code.
- Backend tests should verify completion atomically consumes the code and creates Child Device, Active Enrollment, Child Device Credential, desired Policy Application State, and Pending Supervision Health.
- Backend tests should verify completion records Role Lock active on the Active Enrollment.
- Backend tests should verify completion stores the Enrollment Code reference on the Active Enrollment or equivalent audit record.
- Backend tests should verify completion rejects reused codes.
- Backend tests should verify completion rejects stale codes after another Active Enrollment exists for the Child Profile.
- Backend tests should verify invalid proof-of-possession does not consume the Enrollment Code.
- Backend tests should verify v1 does not require hardware attestation.
- Backend tests should verify completion response omits Household ID and full policy body.
- Backend tests should verify completion issues a fifteen-minute Child Device JWT with expected identity claims.
- Backend tests should verify child actor resolution checks current Child Device Credential and Active Enrollment state.
- Backend tests should verify revoked credentials or revoked/ended Active Enrollments cannot receive new JWTs.
- Backend tests should verify token challenges are backend-issued, short-lived, one-use, and tied to a credential.
- Android Guardian tests should use a fakeable Enrollment Code client/repository or coordinator seam rather than real Convex.
- Android Guardian tests should verify QR generation, expiry/countdown state, regeneration, cancellation, stale-code transition, and enrollment-completed auto-transition.
- Android Guardian tests should verify QR UI is available only for Unenrolled Child Profiles.
- Android Child tests should use a fakeable Device Identity enrollment client and local enrollment state repository.
- Android Child tests should verify scanner routing is available only after Protection Setup.
- Android Child tests should verify malformed QR payloads are rejected before backend calls.
- Android Child tests should verify preview success, preview invalid-code handling, and network error handling.
- Android Child tests should verify completion generates or uses Keystore key material through an abstraction suitable for JVM tests.
- Android Child tests should verify successful completion persists local enrollment state before policy fetch is invoked.
- Android Child tests should verify the Enrollment Code and raw QR payload are not retained in local enrollment state.
- Android Child tests should verify policy fetch happens after enrollment state persistence and that policy-controlled behavior does not start before policy storage.
- Android Child tests should verify app-facing errors map to stable invalid code, already enrolled, generic failure, and network unavailable states.
- Compose or instrumented tests should be added only where scanner UI, QR rendering, or platform Keystore behavior cannot be validated through plain JVM seams.
- Prior art should follow the Guardian auth bootstrap and Guardian Child Profile onboarding style: public backend function tests, fake Android clients/repositories, state/coordinator assertions, and no assertions on private coroutine/helper structure.

## Out of Scope

- Full Supervision Policy editor UI.
- Guardian dashboard analytics, location state, Safety Alerts, Screen Time Summaries, Access Requests, and broader dashboard polish.
- Human-typeable manual Enrollment Codes.
- Coarse rate limiting for invalid enrollment attempts.
- Android hardware-backed key attestation or Play Integrity enforcement.
- Reusable Child Device bearer refresh tokens.
- Two-phase or resumable enrollment completion.
- Child Device key rotation beyond preserving the separate credential model.
- Multiple active Child Device Credentials for one Active Enrollment.
- Multiple active Child Devices for one Child Profile.
- Replace Child Device implementation, except that failure/recovery states should be compatible with a later Replace Child Device flow.
- End Supervision implementation.
- FCM token registration inside enrollment completion.
- Initial policy delivery inside enrollment completion.
- Child Device command creation for initial policy fetch.
- Full Supervision Health heartbeat implementation beyond creating Pending Supervision Health where needed by enrollment.
- Production abuse-prevention hardening beyond high-entropy codes, five-minute expiry, hashed storage, generic invalid errors, and no raw-code logging.

## Further Notes

- This PRD follows ADR-0006 by allowing one active Child Device per Child Profile.
- This PRD follows ADR-0007 by enrolling Child Devices with short-lived codes.
- This PRD follows ADR-0026 by requiring Protection Setup before pairing and delaying monitoring until pairing and policy application.
- This PRD follows ADR-0031 by keeping Guardian Account identity separate from Child Device identity.
- This PRD follows ADR-0034 by requiring every Convex operation to authorize by actor and resource.
- This PRD follows ADR-0035 and ADR-0054 by using desired and applied policy versions rather than claiming policy is applied during enrollment.
- This PRD follows ADR-0047 by using domain-oriented tables for Child Devices, Active Enrollments, Child Device Credentials, and policy application state.
- This PRD follows ADR-0052 by creating Child Profiles and Initial Supervision Policies before Child Device enrollment.
- This PRD follows ADR-0053 by completing enrollment through Protection Setup, Enrollment Code exchange, device keying, Active Enrollment creation, Child Device Credential creation, and Child Device JWT issuance.
- This PRD follows ADR-0059 by making completion strict and non-resumable in v1.
- This PRD follows ADR-0060 by allowing one active Enrollment Code per Child Profile.
- This PRD follows ADR-0061 by expiring Enrollment Codes after five minutes.
- This PRD follows ADR-0062 by keeping Enrollment Codes QR-only in v1.
- This PRD follows ADR-0063 by storing only hashed Enrollment Codes.
- This PRD follows ADR-0064 by using versioned QR payloads with random enrollment tokens.
- This PRD follows ADR-0065 by placing Child enrollment under Device Identity HTTP endpoints.
- This PRD follows ADR-0066 by using fifteen-minute Child Device JWTs.
- This PRD follows ADR-0067 by refreshing Child Device JWTs through backend-issued Keystore challenges.
- The backend data model should remain consistent with the glossary decision that Prepared Child Device is local-only.
- The most important implementation risk is the backend-success/local-persistence-failure edge case. V1 intentionally accepts Guardian recovery rather than a resumable enrollment protocol.
