Status: ready-for-agent

# Reconcile apply-policy-version commands end to end

## Parent

`.scratch/authoritative-messaging-fcm-delivery/PRD.md`

## What to build

Deliver the first complete Child Device Command tracer bullet using `apply_policy_version`. Enrollment and later desired-policy changes must create an authoritative command that references, but never contains, the desired Supervision Policy version. Child Mode must re-register its development FCM token using the shared exclusive-owner contract, fetch authoritative commands after a minimal generic wake-up and through startup/periodic fallback, fetch and apply the referenced policy, persist local application state, and complete both Policy Application State and command acknowledgement at the established success point.

Invalidate the existing development Child token rows and let normal authenticated re-registration replace them without changing Child Device identity, Active Enrollment, Role Lock, or Child Device Credential state.

## Acceptance criteria

- [ ] Existing development Child FCM token rows are invalidated and normal client re-registration preserves all identity, credential, enrollment, and Role Lock state.
- [ ] Child token registration uses the established authenticated Child actor, shared hashing/encryption keyring, exclusive active-owner rule, startup/periodic retry, and Firebase token-rotation handling.
- [ ] Enrollment creates one idempotent pending `apply_policy_version(1)` command targeted to its Active Enrollment.
- [ ] The command references the authoritative Supervision Policy version and contains no policy body or authoritative feature state.
- [ ] A private delivery action sends a normal-priority data-only wake-up containing only schema version, opaque command identifier, and generic Child Device Command category; it does not reveal command type or policy version.
- [ ] Child Mode uses one reconciliation path after FCM receipt, on startup, and through the existing periodic supervision worker, fetching pages of at most 50 independently processable commands.
- [ ] Child Mode fetches the referenced authoritative policy, starts/applies it locally, persists application state, and only then acknowledges policy and command completion.
- [ ] The existing matching policy acknowledgement idempotently completes the corresponding command, including when acknowledgement arrives before a repeated command fetch.
- [ ] Fetching the command or FCM acceptance never acknowledges it, and duplicate push/fetch/acknowledgement produces at most one effective policy application.
- [ ] Replace Child Device, End Supervision, Child Device revocation, or inactive enrollment prevents command access/delivery and revokes relevant token bindings.
- [ ] Backend tests exercise the authenticated Child HTTP boundary and fake FCM gateway; Child JVM tests exercise ordering, persistence, retry, duplicate delivery, and periodic fallback through existing coordinator seams.

## Blocked by

None - can start immediately.
