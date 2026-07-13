Status: ready-for-agent

# Cool Guardian Notifications and Wake Every Guardian Device

## Parent

[Active Screen Safety Detection](../PRD.md)

## What to build

Extend Safety Alert ingestion with authoritative, transactionally enforced Guardian notification eligibility. A fresh alert outside the fixed cooldown creates one Guardian Notice and begins a two-minute window keyed by Child Profile and detection type. A novel alert inside the window is still stored but creates no Notice, does not extend the window, and never produces a delayed wake-up. An alert received more than five minutes after occurrence is also stored without an immediate Notice.

For every approved Notice, Convex sends a generic high-priority FCM wake-up to all active Guardian Devices. Guardian Mode treats push only as a reconciliation trigger, obtains authoritative Notice and alert data after authentication, shows generic system-notification wording, opens the relevant Child safety feed, and converges read state across duplicate, missed, or out-of-order delivery.

## Acceptance criteria

- [ ] Notification cooldown defaults to a configurable app-owned two minutes and is keyed independently by Child Profile and `scam_text` or `nsfw_screen` detection type.
- [ ] Alert storage, freshness evaluation, cooldown evaluation, Notice creation, and cooldown advancement are concurrency safe and cannot create multiple allowed Notices for the same window.
- [ ] A novel alert inside cooldown is stored and visible in the feed, creates no Guardian Notice or FCM, and still leaves its local Child intervention unaffected.
- [ ] A suppressed alert does not extend the fixed window, and cooldown expiry alone never creates a delayed Notice.
- [ ] An alert received more than five minutes after its occurrence is stored without a Notice or FCM.
- [ ] Scam and NSFW cooldowns are independent; different Child Profiles are independent; all Guardian Devices share the same backend-authoritative result.
- [ ] One approved Notice fans out as a generic high-priority FCM wake-up to every active Guardian Device and excludes inactive devices.
- [ ] FCM payloads contain no Child identity, detection type, package, app, confidence, incident details, or captured content.
- [ ] The Child never selects recipients or sends FCM directly.
- [ ] Guardian Mode treats FCM as a wake-up, reconciles authoritative Notices from Convex, and handles missed, duplicate, and out-of-order pushes idempotently.
- [ ] The Guardian system notification uses generic safety wording, may resolve the Child display name locally, and exposes no detector, app, confidence, or content on the lock screen.
- [ ] Tapping the notification opens the authenticated selected-Child safety feed, where type, app, occurrence time, and confidence band are shown.
- [ ] Opening an approved Notice uses the existing acknowledgement lifecycle so active Guardian Devices converge on read state.
- [ ] Automated tests cover exact cooldown and freshness boundaries, concurrency, two Children, both detector types, multi-device fanout, generic payload privacy, reconciliation, navigation, acknowledgement, and suppressed alerts without Notices.
- [ ] Real-device acceptance verifies prompt delivery and reconciliation on two active Guardian Devices and confirms the system notification remains generic.

## Blocked by

- [02 – Deliver a Metadata-Only Safety Incident to the Guardian Feed](02-deliver-metadata-only-safety-incident.md)

