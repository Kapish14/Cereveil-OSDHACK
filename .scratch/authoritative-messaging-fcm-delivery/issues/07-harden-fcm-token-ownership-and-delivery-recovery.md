Status: ready-for-agent

# Harden FCM token ownership and delivery recovery

## Parent

`.scratch/authoritative-messaging-fcm-delivery/PRD.md`

## What to build

Harden the shared development FCM delivery path now exercised by both Guardian Notices and Child Device Commands. Verify exclusive cross-role token ownership, lifecycle revocation, permanent invalid-token retirement, bounded transient recovery with jitter, and failure isolation across targets. Delivery observability must expose safe outcomes without leaking credentials, token values, Child information, policy data, capability payloads, or notification content.

## Acceptance criteria

- [ ] The same token hash can have only one active Guardian Device or Child Device owner within development, including cross-role reassignment and concurrent registration attempts.
- [ ] Rotation leaves the authenticated delivery owner on one current active binding and retires its obsolete binding without changing device identity.
- [ ] Guardian and Child lifecycle deactivation paths revoke all applicable active bindings and prevent later sends.
- [ ] Definitive FCM unregistered/invalid-token responses mark only the affected binding invalid and prevent repeated targeting.
- [ ] Ambiguous, rate-limited, unavailable, and other classified transient responses do not invalidate the token.
- [ ] Transient failures retry at most five times over approximately 15 minutes with bounded jitter and persist enough attempt state for idempotent scheduled execution.
- [ ] One target's permanent or transient failure does not block delivery attempts or reconciliation for other target devices.
- [ ] FCM acceptance remains delivery evidence only and cannot acknowledge any Guardian receipt or Child command.
- [ ] Automated fake-gateway tests verify payload minimization, priority selection, successful delivery, permanent invalidation, transient scheduling, exhaustion, jitter bounds, and target isolation.
- [ ] Operational records/logs contain only allowlisted delivery outcomes and correlation data, excluding plaintext/encrypted token values, service credentials, Child identity, notice content, policy information, and capability payloads.
- [ ] All sending remains confined to the configured development Firebase project and private Convex Node action.

## Blocked by

- `01-deliver-an-offline-notice-end-to-end.md`
- `05-reconcile-apply-policy-version-commands-end-to-end.md`
