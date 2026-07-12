Status: ready-for-agent

# Harden policy reconciliation across Offline and concurrent Guardian activity

## Parent

[Supervision Policy Lifecycle Foundation](../PRD.md) — user stories 10–14, 23–33, 59–65, 67–72, and 77–80.

## What to build

Harden and verify the completed policy lifecycle under the failure and concurrency conditions that cross feature boundaries. Demonstrate that uncertain Guardian responses, two active Guardian Devices, Offline Child Mode, multiple desired feature changes, command supersession, process death, schema incompatibility, startup/periodic fallback, and permanent failure all converge on one authoritative desired/applied result without lost updates or false application claims.

Finish with a development smoke path from a Guardian feature tap through immutable policy creation, generic FCM wake-up or fallback reconciliation, Child atomic acceptance and acknowledgement, and authoritative completion on Guardian Mode. FCM must remain optional for correctness.

## Acceptance criteria

- [x] A committed Guardian save whose response is lost is recovered by replaying the same operation identifier and returns the original version without duplicate policy, command, or delivery work.
- [x] Two Guardian Devices editing the same base version produce one winner and one stable stale-policy conflict; the losing device reloads current authoritative state before another attempt.
- [x] Guardian optimistic state is discarded on rejection and pending/applied/failed state is reconstructed correctly after navigation, process recreation, and on the other Guardian Device.
- [x] An Offline Child Device leaves desired state pending, preserves its accepted offline policy, and changes inline spinner to Waiting for Child Device without blocking unrelated feature changes.
- [x] Multiple feature changes while Offline create complete successive desired snapshots, preserve every earlier desired section, and leave only the latest effective policy-reconciliation command pending.
- [x] Startup, periodic work, resume/wake-up integration, duplicate FCM, lost FCM, duplicate command fetch, token refresh, and duplicate acknowledgement converge on one latest applied version.
- [x] Child process death before activation, after activation but before accepted persistence, and after accepted persistence but before acknowledgement cannot load an unaccepted policy or perform duplicate effective application.
- [x] Convex rejects a policy change above the Active Enrollment's reported supported schema; an independently unsupported Child policy is rejected safely without replacing accepted state.
- [x] No stale acknowledgement or rejection can overwrite newer desired, applied, or failed Policy Application State.
- [x] Superseded policy versions remain queryable internally for diagnosis and are still deleted with the Child Profile during End Supervision.
- [x] The full backend and Android test suites relevant to enrollment, policy, commands, messaging, Guardian controls, and Supervision Health pass together.
- [x] A documented development smoke procedure verifies Guardian change, authoritative version/command records, FCM-minimal delivery or fallback, Child application, acknowledgement, and final Guardian control state without recording sensitive policy or device data.

## Blocked by

- [03 — Configure Safe Browsing and Safe Search as one validated policy section](03-configure-safe-browsing-and-safe-search-as-one-validated-policy-section.md)
- [04 — Configure App Blocking and Active Screen Safety through independent policy sections](04-configure-app-blocking-and-active-screen-safety-through-independent-sections.md)
- [05 — Report permanent Supervision Policy application failure end to end](05-report-permanent-policy-application-failure-end-to-end.md)
