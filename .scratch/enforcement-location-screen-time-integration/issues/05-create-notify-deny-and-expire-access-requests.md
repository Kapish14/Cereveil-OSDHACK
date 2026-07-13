Status: ready-for-agent

# Create, notify, deny, and expire Access Requests

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 45–54 and 65–66.

## What to build

Connect the Block Screen's Ask Guardian action to an authoritative Access Request lifecycle. An authenticated Child Device can request access only for a package actually blocked by its applied policy. Convex deduplicates the effective block, notifies every active Guardian Device, accepts the first valid resolution, and expires requests when time, policy intent, effective schedule coverage, enrollment, or supervision makes them ineligible.

This slice completes denial end to end. Denial creates no grant, uses normal-priority delivery, leaves enforcement unchanged, and starts a backend-owned five-minute cooldown. Offline taps are not queued and explain that the Guardian is unreachable.

## Acceptance criteria

- [ ] Child request creation derives ownership from the full authenticated actor chain and validates that the package is blocked under the referenced applied policy and effective block description.
- [ ] At most one pending request exists per active enrollment, package, and materially equivalent effective block; repeated taps return it without duplicate notices.
- [ ] Offline Block Screen behavior creates no queued request and clearly states that the Guardian is unreachable.
- [ ] Each active Guardian Device receives one authorized Access Request notice with independent receipt state and no sensitive FCM payload content.
- [ ] The first valid Guardian response resolves the request transactionally; later or concurrent responses observe the terminal result and cannot contradict it.
- [ ] Requests expire no later than 15 minutes, the scheduled block's continuous coverage end, material block change/removal, or Active Enrollment/Supervision end.
- [ ] Unrelated policy versions preserve the request when the same effective block remains, while a desired policy that removes the block prevents approval even if application is still pending.
- [ ] Denial creates no Access Grant, retains the Block Screen, starts a five-minute server-owned cooldown, and uses normal-priority delivery.
- [ ] Access Request/denial wake-ups use the generic command/notice contracts and expose only opaque identifiers.
- [ ] Backend integration, FCM gateway, Guardian UI/ViewModel, Block Screen, concurrency, expiry, authorization, Offline, and policy-change tests cover the complete denial path.

## Blocked by

- [03 — Generalize typed Child Device Commands and FCM reconciliation](03-generalize-typed-child-device-commands-and-fcm-reconciliation.md)
- [04 — Enforce Manual and Scheduled App Blocks on Android](04-enforce-manual-and-scheduled-app-blocks-on-android.md)
