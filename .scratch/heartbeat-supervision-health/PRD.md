Status: ready-for-agent

# Heartbeat and Supervision Health

## Problem Statement

Cereveil can enroll a Child Device, create Pending Supervision Health, apply an Initial Supervision Policy, and send a first authenticated heartbeat, but it does not yet maintain an ongoing authoritative view of whether the Child Device is reporting or whether the capabilities completed through Protection Setup remain available. The existing backend also stores pending, protected, degraded, and offline values in one status field, which conflates connectivity with the last reported protection condition. A Guardian therefore cannot reliably see Online or Offline state, retain the last known protection state while a device is Offline, identify unavailable capabilities, or understand when the device was last checked.

The current Child enrollment flow also conflates policy application with heartbeat delivery. It acknowledges policy before starting the local policy runtime, reports policy as not applied when only heartbeat delivery fails, and retries failed work only when the enrollment ViewModel is recreated. This can make Child Mode and Guardian Mode overstate or misstate operational readiness.

## Solution

Build the first complete Supervision Heartbeat and Supervision Health slice. Child Mode will use one unique WorkManager supervision-sync job to run approximately every 15 minutes, retry a locally pending policy acknowledgement when necessary, and independently submit an authenticated Supervision Heartbeat containing the current Protection Setup capability snapshot. WorkManager will use network constraints and exponential backoff for transient failures while stopping retries for a definitively revoked or inactive enrollment.

Convex will remain the authoritative source of connectivity and protection state. Enrollment will create pending connectivity and protection state and schedule an initial guarded Offline check. Every accepted heartbeat will record server receipt time, mark the Active Enrollment Online, update its last reported protection state and capabilities, and schedule another guarded Offline check for 45 minutes later. A check will mark connectivity Offline only when no newer heartbeat has arrived. Offline state will not erase the last reported protection condition.

Guardian Mode will show compact connectivity and protection labels on each Child dashboard card and detailed last-checked or last-seen information plus unavailable capabilities on the Child detail/status surface. Actual Location Heartbeats, FCM delivery, and Guardian Notices remain later work.

## User Stories

1. As a Guardian, I want to see whether each enrolled Child Device is Online or Offline, so that I know whether Cereveil is recently reporting from it.
2. As a Guardian, I want Online status to come from an authenticated report received by Convex, so that the Child Device does not declare its own connectivity state.
3. As a Guardian, I want a Child Device to become Offline after 45 minutes without an accepted Supervision Heartbeat, so that ordinary Android scheduling delays have more tolerance than the previous 30-minute concept.
4. As a Guardian, I want an enrolled Child Device to remain pending before its first heartbeat, so that successful enrollment is not immediately presented as either Online or Offline without evidence.
5. As a Guardian, I want a newly enrolled Child Device that never sends its first heartbeat to become Offline after 45 minutes, so that it cannot remain pending forever.
6. As a Guardian, I want the next accepted heartbeat to restore an Offline Child Device to Online immediately, so that recovered connectivity is reflected without waiting for another scheduled check.
7. As a Guardian, I want connectivity and protection to be separate states, so that going Offline does not erase the last known protection condition.
8. As a Guardian, I want to see Fully Protected when every capability required by the hackathon Protection Setup was available in the latest heartbeat, so that I can understand the latest reported configuration.
9. As a Guardian, I want to see Protection Degraded when at least one required capability was unavailable, so that capability loss is visible.
10. As a Guardian, I want to see which required capabilities were unavailable, so that a generic degraded label is actionable.
11. As a Guardian, I want an Offline Child Device to retain its last reported Fully Protected or Protection Degraded state, so that connectivity loss does not destroy useful context.
12. As a Guardian, I want Offline degraded state to be described as the state when last checked, so that stale capability information is not presented as current fact.
13. As a Guardian, I want Online details to show how long ago the device was last checked, so that I understand that Online is based on periodic reporting rather than a continuous connection.
14. As a Guardian, I want Offline details to show how long ago the device was last seen, so that I can judge the duration of the outage.
15. As a Guardian, I want relative last-checked and last-seen text to update locally, so that the app does not query Convex every minute merely to refresh a label.
16. As a Guardian supervising multiple Children, I want dashboard cards to show compact connectivity and protection labels, so that the dashboard remains scannable.
17. As a Guardian, I want detailed timing and unavailable capabilities on a Child detail/status surface, so that card summaries remain concise.
18. As a Guardian, I want policy application state to remain distinct from Supervision Health, so that an acknowledgement failure is not confused with heartbeat failure.
19. As a Guardian, I want an Online Child Device to be allowed to show Policy Pending, so that independent operational states are represented truthfully.
20. As a Child using the Child Device, I want supervision reporting to use battery-conscious periodic work, so that Cereveil does not continuously poll or keep the device awake.
21. As a Child using the Child Device, I want capability changes to be reported by the next scheduled heartbeat rather than immediate event-driven reporting in this version, so that the hackathon implementation remains simple.
22. As a Child using the Child Device, I want transient network failures to use controlled backoff rather than rapid retry loops, so that Cereveil limits battery, data, and backend load.
23. As a Child using the Child Device, I want duplicate periodic workers to be prevented, so that enrollment or app startup cannot multiply heartbeat traffic.
24. As a Child using the Child Device, I want the existing immediate first heartbeat to remain, so that Guardian Mode can receive status without waiting for the first periodic interval.
25. As a Child using the Child Device, I want recurring work to survive ordinary process death and device restart according to Android WorkManager behavior, so that supervision reporting does not depend on keeping the enrollment screen open.
26. As a Child using the Child Device, I want expired Child Device JWTs refreshed through the existing Keystore challenge flow before authenticated sync, so that recurring work does not require a reusable bearer refresh token.
27. As a Child using the Child Device, I want a definitive revoked credential or inactive enrollment response to stop retries for that enrollment, so that an old Child Device does not retry forever.
28. As a Child using the Child Device, I want terminal authorization failure not to clear Role Lock or local enrollment automatically, so that background sync cannot become an unauthorized unenrollment mechanism.
29. As a Child using the Child Device, I want policy runtime startup to happen before policy acknowledgement, so that Convex does not record a policy as applied before it is running locally.
30. As a Child using the Child Device, I want a pending policy acknowledgement retried after transient failure, so that acknowledgement does not depend on recreating the enrollment screen.
31. As a Child using the Child Device, I want policy acknowledgement retried only when local versions show that applied policy is still unacknowledged, so that the policy is not fetched or acknowledged every 15 minutes.
32. As a Child using the Child Device, I want policy acknowledgement and heartbeat submission to be independent operations, so that failure of one does not prevent the other.
33. As a Child using the Child Device, I want successful policy acknowledgement persisted locally, so that a later heartbeat retry does not unnecessarily repeat completed work.
34. As a Child using the Child Device, I want a successful heartbeat preserved when policy acknowledgement fails, so that Convex can still mark the device Online and record capabilities.
35. As a Child using the Child Device, I want successful policy acknowledgement preserved when heartbeat submission fails, so that Guardian Mode does not regress policy state.
36. As a developer, I want an accepted heartbeat to use Convex server receipt time, so that client clock drift cannot manipulate connectivity freshness.
37. As a developer, I want liveness derived from receipt of a valid authenticated heartbeat rather than a client-supplied Online boolean, so that connectivity state remains authoritative.
38. As a developer, I want every scheduled Offline check guarded by the latest heartbeat timestamp, so that an older check cannot mark a recently reporting device Offline.
39. As a developer, I want the initial Offline check scheduled during enrollment, so that absence of the first heartbeat is handled.
40. As a developer, I want per-heartbeat Offline checks scoped to one Active Enrollment, so that the initial implementation does not repeatedly scan every healthy device with a global cron.
41. As a developer, I want the heartbeat capability payload to omit GPS coordinates, so that Supervision Heartbeat does not become Location Heartbeat.
42. As a developer, I want actual Location Heartbeat work excluded from this slice, so that location collection and freshness retain their own semantics.
43. As a developer, I want VPN capability removed from the hackathon heartbeat contract, so that an explicitly excluded future feature cannot incorrectly degrade current health.
44. As a developer, I want future Safe Browsing and VPN architecture retained, so that removing the current placeholder does not reverse production planning.
45. As a developer, I want Child Device calls to keep using the existing custom JWT HTTP boundary for this slice, so that custom Convex JWT/JWKS migration does not block hackathon progress.
46. As a developer, I want a focused shared Child HTTP wrapper where practical, so that JWT verification, actor resolution, and safe failures are not repeatedly reimplemented.
47. As a developer, I want Guardian health reads to use the existing Guardian query wrapper, so that Clerk authentication and GuardianActor resolution remain consistent.
48. As a developer, I want FCM token registration to remain independent of Supervision Heartbeat, so that push availability never determines Online status.
49. As a developer, I want Offline, Recovery, and Tamper Notices excluded until the notice infrastructure exists, so that this slice does not make FCM a prerequisite.
50. As a developer, I want the empty current development database to permit a direct schema change, so that unnecessary migration machinery is not introduced.

## Implementation Decisions

- Implement Heartbeat and Supervision Health as the next feature after Child Device enrollment and Guardian Child Profile onboarding.
- Keep three delivery stages distinct: Supervision Health first, FCM token completion later, and Guardian Notice infrastructure after that.
- Use Supervision Heartbeat as the canonical term for the authenticated liveness and protection-capability report.
- Keep Supervision Heartbeat distinct from Location Heartbeat. This slice sends no GPS coordinates and implements no location measurement storage or display.
- Retain the existing immediate heartbeat after initial local policy application and add ongoing periodic reporting.
- Use one uniquely named Android WorkManager supervision-sync job per active local enrollment.
- Request periodic work approximately every 15 minutes with network connectivity required. Treat Android timing as best-effort rather than exact.
- Prevent overlapping or duplicate periodic workers.
- Use WorkManager exponential backoff for transient failures, with an initial delay around 10 minutes and no custom rapid-retry loop.
- Classify network failures, temporary backend failures, and recoverable token-refresh failures as transient.
- Classify a definitively revoked Child Device Credential or inactive Active Enrollment as terminal for that enrollment's worker.
- Do not automatically clear Role Lock or local enrollment state after terminal sync authorization failure.
- Make the periodic worker a supervision-sync cycle with two independent operations: retry a locally pending policy acknowledgement when needed, and submit a Supervision Heartbeat.
- Do not fetch the Supervision Policy on every periodic cycle.
- Persist the locally applied policy version and the last successfully acknowledged version, or equivalent local state, so acknowledgement is attempted only while pending.
- Preserve success independently. A successful policy acknowledgement must not be repeated merely because heartbeat failed, and a successful heartbeat must update health even when acknowledgement failed.
- Start the local policy runtime before acknowledging that policy version to Convex.
- Stop using heartbeat delivery success as the meaning of the Child UI's policy-applied state.
- Allow the worker to run once local policy runtime has started; pending acknowledgement does not block heartbeat reporting.
- Continue using the existing Child Device JWT, Keystore challenge refresh, and Device Identity HTTP boundary for authenticated Child calls.
- Do not migrate Child Mode to native Convex custom JWT authentication, asymmetric signing, JWKS publication, or a Convex Android auth provider in this slice.
- Add only focused shared Child HTTP request handling needed to avoid duplicating authentication, actor resolution, and safe-error behavior; do not expand this feature into the entire planned request-context and logging framework.
- Define liveness as Convex receiving and accepting an authenticated Supervision Heartbeat for an active Child Device actor.
- Do not accept client-supplied Online, Offline, or last-heartbeat timestamps.
- Record `lastHeartbeatAt` using Convex server time.
- Replace the single health status dimension with independent connectivity and protection dimensions.
- Connectivity states are Pending, Online, and Offline.
- Protection states are Pending Supervision Health, Fully Protected, and Protection Degraded.
- Enrollment creates pending connectivity and pending protection with no capability snapshot.
- Enrollment schedules a guarded Offline check for 45 minutes after enrollment so a missing first heartbeat does not remain pending forever.
- Every accepted heartbeat marks connectivity Online, updates `lastHeartbeatAt`, stores the capability snapshot, derives protection state, and schedules a guarded Offline check for 45 minutes later.
- An Offline check updates connectivity only if no newer heartbeat has arrived since the check was scheduled.
- A later accepted heartbeat restores Online immediately.
- Offline does not erase the last protection state, capability snapshot, or the time that snapshot was reported.
- Existing Convex data is empty and disposable, so change the schema directly rather than using widen-migrate-narrow or a backfill.
- The Stage 1 capability snapshot contains Accessibility service, Usage Access, location permission, microphone permission, notification permission, and battery-optimization exemption.
- Treat every capability currently completed through hackathon Protection Setup as mandatory for Fully Protected, regardless of which implemented supervision features are enabled.
- Derive Protection Degraded when any required capability is false and expose the unavailable capability identifiers to Guardian Mode.
- Remove VPN from the current Android capability model, HTTP heartbeat contract, Convex validator and storage, health calculation, tests, and Guardian display.
- Retain future Safe Browsing/VPN domain language and architectural decisions; reintroduce VPN health only when VPN joins implemented Protection Setup.
- Extend the normal Guardian Child dashboard rather than limiting health display to the post-enrollment screen.
- Dashboard cards show compact connectivity and protection labels.
- The Child detail/status surface shows relative `Last checked` while Online and `Last seen` while Offline, plus the unavailable-capability list when degraded.
- Before the first heartbeat, show that the app is waiting for the first device status rather than claiming Online.
- If the first heartbeat never arrives and the initial deadline passes, show Offline with protection still pending.
- When Offline with prior degraded protection, describe protection as degraded when last checked.
- Return `serverNow` with Guardian health reads so Android can calculate a server-clock offset.
- Update relative time locally approximately once per minute; do not query Convex every minute only to advance display text.
- Keep policy application state visible independently from connectivity and protection.

## Testing Decisions

- Test externally observable state transitions and retry outcomes rather than private helper decomposition, exact coroutine structure, or exact WorkManager scheduling timestamps.
- Use the public Child Device HTTP boundary and Guardian Convex query as the primary backend test seams with `convex-test`.
- Backend tests cover enrollment-created pending connectivity and protection, including the scheduled initial Offline deadline.
- Backend tests cover authenticated heartbeat receipt, server-owned timestamping, Online transition, protection derivation, stored capabilities, and the next scheduled Offline deadline.
- Backend tests cover a guarded old Offline check doing nothing after a newer heartbeat.
- Backend tests cover Online to Offline after 45 minutes and Offline to Online recovery on the next heartbeat.
- Backend tests cover Offline retaining the last Fully Protected or Protection Degraded state and capability snapshot.
- Backend tests cover absence of the first heartbeat becoming Offline while protection remains pending.
- Backend tests cover unauthorized, revoked-credential, inactive-enrollment, and malformed-capability requests.
- Backend tests verify VPN is absent from the heartbeat contract and stored capability shape.
- Backend tests verify Guardian authorization and the returned connectivity, protection, capability, `lastHeartbeatAt`, and `serverNow` fields.
- Use a fakeable Child Device client, local supervision-sync state store, capability provider, token provider, and coordinator/worker seam for Child JVM tests.
- Child tests cover runtime startup before policy acknowledgement.
- Child tests cover local policy application state remaining successful when heartbeat fails.
- Child tests cover pending acknowledgement and heartbeat being attempted independently.
- Child tests cover preserving each successful operation when the other fails.
- Child tests cover skipping acknowledgement when the locally applied and acknowledged versions match.
- Child tests cover expired JWT refresh before sync.
- Child tests cover transient failure requesting WorkManager retry and terminal authorization failure stopping retries.
- Child tests cover the required capability payload and VPN exclusion.
- Child tests cover unique periodic-work enqueue behavior through an abstraction where a plain JVM seam is practical; avoid asserting Android framework internals.
- Use a fakeable Guardian health client and ViewModel or presentation mapper seam for Guardian JVM tests.
- Guardian tests cover pending, Online, Offline, Fully Protected, and Protection Degraded combinations.
- Guardian tests cover unavailable capability labels, Offline last-known degradation wording, and last-checked versus last-seen formatting.
- Guardian tests cover server-time offset use for relative time without depending on the real wall clock.
- Follow existing prior art: `convex-test` at public backend boundaries and fake Android clients/stores with coordinator and state assertions.
- Add Compose or instrumented tests only for behavior that cannot be exercised through the presentation seam; do not assert layout structure for its own sake.
- Run focused backend and Android test files during implementation, typecheck regularly, and run the full relevant test suite before completion.

## Out of Scope

- Location Heartbeat implementation, GPS coordinate collection, latest location storage, location freshness UI, and Live Location Sessions.
- FCM token registration changes beyond preserving the existing independent flow.
- Guardian Notice infrastructure.
- Offline Notices, Recovery Notices, Tamper Alerts, or push delivery for health changes.
- Event-driven immediate heartbeat submission when a capability changes.
- A delayed or unknown intermediate connectivity label between Online and Offline.
- Policy-dependent required capability sets.
- VPN setup, VPN capability reporting, and Safe Browsing implementation in the hackathon build.
- Polling Convex for policy changes every 15 minutes.
- Native Convex custom-JWT authentication for Child Mode, asymmetric Child Device JWT migration, JWKS publication, and Child Convex auth-provider integration.
- Completing the full planned request-context, safe-logging, and error-mapping middleware architecture.
- Replace Child Device and End Supervision behavior beyond stopping sync retries for definitively inactive authorization.
- Automatic local unenrollment or Role Lock removal.
- Exact 15-minute delivery guarantees that Android consumer background scheduling cannot provide.
- Historical heartbeat or capability timelines; only current health and last report information are required.

## Further Notes

- This PRD follows the domain distinction between Supervision Heartbeat and Location Heartbeat established during design.
- This PRD follows the consumer Google Play distribution decision and therefore treats background delivery as best-effort rather than device-owner guaranteed.
- This PRD follows the decision to use Convex as the authoritative backend and to retain last-known policy while Offline.
- This PRD follows the decision to keep Guardian and Child Device identities separate and to use Child Device Credentials rather than FCM tokens as identity.
- This PRD follows the decision that FCM is a best-effort wake-up transport and not authoritative application state.
- The current implementation already creates Pending Supervision Health, accepts an authenticated first heartbeat, stores capabilities, and exposes enrollment health summary. The feature extends and corrects those seams rather than replacing enrollment.
- The current implementation acknowledges policy before starting the runtime and uses heartbeat success to set a `policyApplied` UI boolean. Implementation must correct both inconsistencies.
- The current implementation retries the initial policy/heartbeat flow only when the enrollment ViewModel is recreated. Persistent supervision-sync work replaces that accidental retry behavior.
- No new ADR is required for the resolved implementation details because the significant architectural choices are already covered by existing Convex, identity, policy, offline, and notification ADRs.
