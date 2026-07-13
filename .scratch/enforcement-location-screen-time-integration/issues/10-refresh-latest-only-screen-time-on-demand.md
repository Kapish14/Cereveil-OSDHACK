Status: ready-for-agent

# Refresh latest-only Screen Time on demand

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 103–129.

## What to build

Give the selected Child Profile an automatically refreshed, latest-only Screen Time surface. When that surface becomes visible and its snapshot is absent, expired, or at least two minutes old, Convex creates one deduplicated refresh and sends a normal-priority generic command. Child Mode queries Android UsageStats for local midnight through measurement time, uploads positive-duration launchable non-exempt per-package foreground totals through bounded staging batches, and atomically publishes one complete replacement.

This is Android's current whole-day total, including usage earlier than enrollment, policy enablement, or a same-day disabled interval. The UI discloses that behavior. Do not calculate sessions, continuously track events, or retain daily/weekly history, raw events, open/close timestamps, or superseded snapshots.

## Acceptance criteria

- [ ] Automatic refresh is triggered only while the selected Child's Screen Time surface is visible and the current snapshot is missing, expired, or at least two minutes old; opening Guardian Mode does not wake every Child.
- [ ] Refresh creation requires desired and applied Screen Time enablement, Online state, and latest Usage Access capability, and is authorized through the Guardian actor wrapper.
- [ ] One pending request per Child Profile deduplicates navigation, recomposition, retry, and a second Guardian Device and expires after two minutes.
- [ ] The request emits a typed `refresh_screen_time` command and normal-priority generic FCM payload with no package or usage content.
- [ ] Child Mode queries Android's per-package `totalTimeInForeground` from current local midnight through measurement time without deriving session counts or continuously collecting Cereveil events.
- [ ] Results include only positive-duration packages present as user-launchable in the latest App Catalog and exclude Cereveil, launcher, System UI, Settings/repair surfaces, Block Screen presentation, and other Exempt Apps.
- [ ] Per-app rows contain package identity and Android-reported duration only; Guardian labels resolve from authorized App Catalog state.
- [ ] A valid empty result publishes successfully, while bounded staging batches remain invisible until expected distinct rows are verified and the replacement header is atomically published.
- [ ] The previous valid same-day snapshot stays visible with “Updating…” while work is pending; Offline, Usage Access unavailable, timeout, failure, empty, and stale states are explicit.
- [ ] Failed or partial uploads are not queued for later publication; abandoned staging expires and a retry queries Android again.
- [ ] Snapshot validity ends at the Child Device's next local midnight, and queries hide invalid rows rather than exposing yesterday as history.
- [ ] Disabling Screen Time immediately deletes backend snapshot, pending and staging state and stops/clears local work.
- [ ] Enable/re-enable UI discloses that Android's whole current-day total may include usage before enrollment, before policy application, and during a same-day disabled interval; Protection Setup and persistent Child status provide ongoing transparency.
- [ ] Backend, FCM, Android UsageStats/coordinator, atomic staging, Guardian ViewModel/Compose, midnight, disablement, deduplication, authorization, and privacy tests cover the full path.

## Blocked by

- [01 — Synchronize the latest App Catalog end to end](01-synchronize-the-latest-app-catalog-end-to-end.md)
- [02 — Apply Supervision Policy v2 and Trusted Device Time](02-apply-supervision-policy-v2-and-trusted-device-time.md)
- [03 — Generalize typed Child Device Commands and FCM reconciliation](03-generalize-typed-child-device-commands-and-fcm-reconciliation.md)
