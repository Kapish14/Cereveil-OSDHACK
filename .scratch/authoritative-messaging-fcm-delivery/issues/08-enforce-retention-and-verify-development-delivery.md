Status: ready-for-agent

# Enforce retention and verify development delivery

## Parent

`.scratch/authoritative-messaging-fcm-delivery/PRD.md`

## What to build

Finish the development messaging slice with bounded retention and a documented real-device smoke test. A daily cleanup process must remove eligible terminal messaging records and expired one-week health notices through indexed, bounded batches. The smoke test must exercise the configured Guardian and Child development Android registrations through Convex and real FCM while proving that authoritative reconciliation recovers when pushes are deliberately missed.

## Acceptance criteria

- [ ] Health notices, eligible Guardian receipts, terminal Child Device Commands, and related terminal delivery records use explicit one-week retention deadlines.
- [ ] A daily scheduled cleanup queries indexed retention/lifecycle fields and deletes bounded pages rather than scanning or mutating the full dataset in one execution.
- [ ] Cleanup preserves pending/actionable records that are not eligible for deletion and is idempotent across retries.
- [ ] Controlled-time tests cover boundary timestamps, mixed eligible/ineligible data, bounded continuation, retry, and eventual complete cleanup without sleeping.
- [ ] A documented development smoke procedure registers one Guardian development build and one Child development build with the configured Firebase project.
- [ ] The smoke procedure verifies a Guardian Offline/Recovery/Tamper wake-up path and the Child `apply_policy_version` wake-up path, followed by authoritative fetch and acknowledgement.
- [ ] The smoke procedure deliberately misses or suppresses a push and verifies Guardian startup/resume and Child startup/periodic reconciliation recover authoritative work.
- [ ] The smoke procedure inspects delivery metadata to confirm Guardian pushes contain no notice/Child details and Child pushes contain no command type, policy version, or policy body.
- [ ] The smoke procedure verifies token rotation or re-registration and, where practical, invalid-token retirement without exposing credential material in documentation or logs.
- [ ] The real Firebase smoke test remains separate from the default automated unit suite and is explicitly development-only.

## Blocked by

- `02-support-independent-multi-guardian-device-reconciliation.md`
- `03-deliver-correlated-recovery-notices.md`
- `04-deliver-deduplicated-tamper-alerts.md`
- `06-complete-the-child-device-command-lifecycle.md`
- `07-harden-fcm-token-ownership-and-delivery-recovery.md`
