Status: ready-for-agent

# Support independent multi-Guardian-Device reconciliation

## Parent

`.scratch/authoritative-messaging-fcm-delivery/PRD.md`

## What to build

Extend the Offline Notice tracer bullet so both allowed Guardian Devices reconcile independently. Each active target must retain its own receipt and presentation lifecycle, one device's acknowledgement must not affect the other, and revoked devices must stop receiving new work. A newly activated Guardian Device may load retained recent Guardian Notice history for in-app context but must establish a baseline that prevents old notices from replaying as new system notifications. Reconciliation must remain bounded, resumable, deterministic, and safe across process death.

## Acceptance criteria

- [ ] Notice creation targets every active Guardian Device at creation time with a separate receipt and excludes revoked devices.
- [ ] Fetching or acknowledging on one Guardian Device does not fetch, acknowledge, suppress, or otherwise mutate another Guardian Device's receipt.
- [ ] Reconciliation paginates deterministically with a maximum page size of 50 and catches up correctly across multiple pages without gaps or duplicates.
- [ ] A newly activated Guardian Device can see retained one-week notice history in authoritative in-app state but does not present that history as new system notifications.
- [ ] Guardian sign-out and Guardian Device revocation revoke its active token bindings and prevent new receipt targeting or push delivery to that device.
- [ ] A process interruption after local cache commit or presentation-decision persistence resumes without losing the notice or presenting it twice.
- [ ] Repeated fetch and acknowledgement requests are idempotent and authorization prevents a Guardian Device from addressing another device's receipt.
- [ ] Integration tests demonstrate two-device independence, revoked-device exclusion, multi-page catch-up, baseline behavior, and process-restart-safe presentation through existing high-level seams.

## Blocked by

- `01-deliver-an-offline-notice-end-to-end.md`
