Status: ready-for-agent

# Enforce Manual and Scheduled App Blocks on Android

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 31 and 33–44.

## What to build

Turn an applied schema-v2 App Blocking section into real Child Device enforcement. Accessibility window state is the primary real-time signal. Child Mode evaluates Manual and recurring Scheduled Blocks locally in the Child Device's current time zone, persists the last accepted policy for offline use, and renders one Cereveil-owned full-screen accessibility Block Screen whenever a blocked package owns a visible interactive window.

The Block Screen explains the effective block and provides Ask Guardian and Home actions. It must intercept interaction in normal, split-screen, and picture-in-picture cases while disappearing promptly for inactive rules, Exempt Apps, Home, system/emergency/repair surfaces, Cereveil, and valid grants. Usage Access may assist reconciliation but must not be represented as a reliable fallback enforcement engine.

## Acceptance criteria

- [ ] Accessibility window events and visible-window inspection drive enforcement promptly for a blocked package in normal, split-screen, and picture-in-picture arrangements.
- [ ] Manual Blocks, multiple recurring schedules, overnight schedules, overlaps, weekday boundaries, current local time zone, and daylight-saving transitions evaluate deterministically on the Child Device.
- [ ] Overlapping schedules compose into continuous coverage, and Manual and Scheduled Blocks remain independent effective reasons.
- [ ] The last accepted policy and required evaluator state survive process death, reboot, and Offline operation without activating an unacknowledged candidate policy.
- [ ] A full-screen `TYPE_ACCESSIBILITY_OVERLAY` prevents interaction with the underlying blocked window and shows an explanation, Ask Guardian, and Home actions.
- [ ] The overlay is removed or withheld for inactive blocks, valid grants, Home/launcher, Cereveil, the Exempt App set, emergency/system surfaces, and Settings/package surfaces needed for repair.
- [ ] OEM/default-handler-specific exemptions are decided by the Child-side classifier even if backend or Guardian metadata is stale.
- [ ] Loss of Accessibility produces Protection Degraded and a deduplicated Tamper Alert; Usage Access is not used to claim continued reliable enforcement.
- [ ] Existing disclosed protection behavior for ordinary capability-revocation flows remains intact rather than blocking Android Settings wholesale.
- [ ] Android unit/integration and Compose/UI tests cover evaluator boundaries, visible-window combinations, overlay lifecycle, Home escape, exemptions, capability loss, process recovery, and offline enforcement.

## Blocked by

- [02 — Apply Supervision Policy v2 and Trusted Device Time](02-apply-supervision-policy-v2-and-trusted-device-time.md)
