Status: ready-for-agent

# Deliver an Offline Notice end to end

## Parent

`.scratch/authoritative-messaging-fcm-delivery/PRD.md`

## What to build

Deliver the first complete Guardian Notice tracer bullet for the existing 45-minute Offline transition. An authenticated Guardian Device must register and rotate an encrypted, owner-bound development FCM token after bootstrap. When the backend genuinely changes an Active Enrollment to Offline, it must create one authoritative Offline Notice and one pending receipt for every active Guardian Device, then send each target only minimal data-only wake-up metadata through the private development FCM gateway. Guardian Mode must fetch the authoritative record, commit it to local notice state, make a permission-aware presentation decision, and acknowledge only its own receipt. Startup, resume, and FCM receipt must all invoke the same reconciliation path, and repeated checks, pushes, fetches, or acknowledgements must remain idempotent.

This slice establishes the shared notice, receipt, token-ownership, delivery-gateway, and Guardian reconciliation seams narrowly enough to demonstrate one real Offline Notice. FCM acceptance records delivery evidence only and must not process the receipt.

## Acceptance criteria

- [ ] Guardian token registration uses the established authenticated Guardian actor, runs after bootstrap, retries on startup/resume, handles Firebase token rotation, and does not depend on notification permission.
- [ ] Token plaintext is encrypted with the configured versioned keyring, equality lookup uses a hash, and registration cannot change or infer Guardian Device identity.
- [ ] Within development, registration atomically invalidates any other active owner binding for the same token hash before activating the authenticated Guardian Device binding.
- [ ] The guarded 45-minute Online/Pending-to-Offline transition creates exactly one authoritative Offline Notice and one receipt per active Guardian Device; stale or repeated checks do not duplicate them.
- [ ] Offline state alone produces no Tamper Alert and all Guardian-facing language remains neutral about why the Child Device stopped reporting.
- [ ] A private Convex Node delivery action sends a normal-priority Android data-only message containing only a schema version, opaque notice identifier, and generic Guardian Notice category.
- [ ] FCM acceptance changes delivery-attempt state only and never acknowledges or processes a receipt.
- [ ] Guardian reconciliation is authorized to the current Guardian Device, deterministically returns its pending authoritative records, and accepts no more than 50 records per page.
- [ ] Guardian Mode invokes the same reconciliation flow after FCM receipt and on application startup/resume, commits a notice to local state and persists its presentation decision before acknowledging it.
- [ ] Notification permission denial results in a persisted no-presentation decision and successful receipt acknowledgement; duplicate reconciliation presents at most one system notification per notice on that Guardian Device.
- [ ] Backend tests exercise the authenticated public boundary with controlled time and a fake FCM gateway; Guardian JVM tests exercise the repository/coordinator, local-store, and presentation-decision boundaries.
- [ ] No Firebase server credential, FCM token plaintext, Child identity, health payload, or notice presentation content is exposed in Android configuration, push metadata, or routine logs.

## Blocked by

None - can start immediately.
