Status: ready-for-agent

# Deliver correlated Recovery Notices

## Parent

`.scratch/authoritative-messaging-fcm-delivery/PRD.md`

## What to build

Model an authoritative Offline episode and deliver a Recovery Notice through the established Guardian Notice path when a later accepted Supervision Heartbeat changes that same Active Enrollment from Offline to Online. The outage and recovery must be correlated and deduplicated. Pending-to-Online and Online-to-Online heartbeats must not be described as recovery.

## Acceptance criteria

- [ ] The first genuine Offline transition opens one stable Offline episode associated with its Offline Notice.
- [ ] The first later authenticated heartbeat that transitions that enrollment from Offline to Online closes the episode and creates exactly one correlated Recovery Notice.
- [ ] Pending-to-Online, Online-to-Online, heartbeat retries, and repeated processing do not create Recovery Notices.
- [ ] Recovery uses the existing per-Guardian-Device receipt, authoritative fetch, local persistence, presentation-decision, and acknowledgement flow.
- [ ] Recovery wake-up delivery is normal-priority and contains only the established non-sensitive generic metadata.
- [ ] Offline and Recovery records remain available for the agreed one-week retention period.
- [ ] Controlled-time integration tests prove exact correlation, deduplication, no false recovery, and end-to-end Guardian reconciliation without relying on FCM as authoritative state.

## Blocked by

- `01-deliver-an-offline-notice-end-to-end.md`
