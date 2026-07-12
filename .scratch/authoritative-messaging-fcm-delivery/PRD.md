Status: ready-for-agent

# Authoritative Messaging and FCM Delivery

## Problem Statement

Cereveil already has an authoritative Supervision Policy flow, authenticated Child Device HTTP endpoints, recurring Supervision Heartbeats, a partial Child FCM token-registration path, and a configured development Firebase project. It does not yet have a complete messaging system that can reliably wake Guardian Mode or Child Mode, reconcile missed work, distinguish delivery from acknowledgement, or preserve correctness when FCM delivery is delayed, duplicated, or lost.

Guardian Devices do not yet register and rotate their own FCM tokens. Existing Child token records use an earlier registration shape and must be replaced safely. There are no authoritative Guardian Notice or Child Device Command records, no per-target receipt lifecycle, no delivery gateway, and no reliable invalid-token handling. Supervision Health can transition Offline or recover Online, and can report degraded protection, but those transitions do not yet create the agreed Offline Notice, Recovery Notice, or Tamper Alert.

Without this work, FCM risks becoming accidental authoritative state: a missed push could mean missed work, a duplicate push could repeat an action, one Guardian Device could hide work from another, and an unexplained Offline device could be incorrectly described as tampered with.

## Solution

Build a development-only authoritative messaging slice around two independent concepts: Guardian Notices for information delivered to every active Guardian Device, and Child Device Commands for bounded work assigned to Child Mode. Convex will own all records, lifecycle transitions, deduplication, authorization, and reconciliation. FCM will carry only minimal non-sensitive wake-up metadata and will never prove that a notice was processed or a command was completed.

Both Android roles will register and rotate encrypted, owner-bound FCM tokens independently from device identity. They will fetch authoritative records after a push and reconcile on startup or resume so correctness does not depend on push delivery. Guardian Mode will acknowledge each notice independently per Guardian Device after committing it to local cache and making a presentation decision. Child Mode will fetch and process commands independently, acknowledging or rejecting each command only at its type-specific completion point.

The initial Child Device Command type will be `apply_policy_version`. It will reference an authoritative Supervision Policy version rather than contain policy state. The initial Guardian Notice types will be Offline Notice, Recovery Notice, and Tamper Alert, derived from authoritative Supervision Health transitions. Delivery will use a private Convex Node action and the configured least-privilege development Firebase service account, with bounded retry and invalid-token retirement.

## User Stories

1. As a Guardian, I want every active Guardian Device on my Guardian Account to receive Guardian Notices, so that supervision information is available on either authorized phone.
2. As a Guardian, I want each Guardian Device to reconcile notices independently, so that one phone cannot hide unread work from another.
3. As a Guardian, I want Guardian Mode to recover notices on startup or resume, so that a missed push does not mean a missed notice.
4. As a Guardian, I want a push to wake Guardian Mode without containing sensitive Child information, so that notification transport does not expose authoritative data.
5. As a Guardian, I want Guardian Mode to fetch the authoritative Guardian Notice before displaying it, so that FCM content cannot become the source of truth.
6. As a Guardian, I want each notice presented as a system notification at most once per Guardian Device, so that retries and duplicate pushes do not create duplicate alerts.
7. As a Guardian, I want notice processing to succeed even when notification permission is denied, so that in-app reconciliation remains correct.
8. As a Guardian, I want notification permission to affect presentation rather than token registration, so that delivery readiness is not conflated with UI permission.
9. As a Guardian, I want new Guardian Devices to see retained recent notice history, so that adding a phone provides useful current context.
10. As a Guardian, I do not want a newly added Guardian Device to replay old notices as new system notifications, so that enrollment does not create an alert storm.
11. As a Guardian, I want an Offline Notice only after the backend's 45-minute Offline transition, so that ordinary heartbeat timing is respected.
12. As a Guardian, I want a Recovery Notice only when a genuinely Offline Child Device later returns Online, so that ordinary Online heartbeats do not create recovery noise.
13. As a Guardian, I want an Offline episode and its Recovery Notice correlated, so that the outage lifecycle is understandable and deduplicated.
14. As a Guardian, I want the first heartbeat that reports a required capability becoming unavailable to create a Tamper Alert, so that protection degradation is surfaced promptly.
15. As a Guardian, I want one Tamper Alert to group all capabilities newly unavailable in the same heartbeat, so that one report does not create many alerts.
16. As a Guardian, I do not want repeated degraded heartbeats to repeat the same Tamper Alert, so that persistent degradation does not create alert fatigue.
17. As a Guardian, I want later capability recovery to resolve the prior degradation without a restoration notice, so that the initial slice remains focused.
18. As a Guardian, I want an unexplained Offline Child Device described only as Offline, so that Cereveil does not accuse the Child of tampering without evidence.
19. As a Guardian, I want Recovery and Tamper Notices to be allowed from the same heartbeat, so that independent state transitions are both represented truthfully.
20. As a Guardian, I want recent health notices retained for one week, so that short-term reconciliation and context remain available.
21. As a Child using a Child Device, I want FCM to wake Child Mode without revealing the command type, so that lock-screen transport metadata remains minimal.
22. As a Child using a Child Device, I want Child Mode to fetch authoritative Child Device Commands after a push, so that the push payload cannot instruct the device directly.
23. As a Child using a Child Device, I want commands reconciled on startup and through the existing periodic supervision work, so that missed pushes do not block supervision.
24. As a Child using a Child Device, I want commands processed independently, so that one stuck command does not block unrelated work.
25. As a Child using a Child Device, I want duplicate command delivery to produce at most one effective action, so that retries remain safe.
26. As a Child using a Child Device, I want a command acknowledged only after its type-specific success point, so that fetching alone is never mistaken for completion.
27. As a Child using a Child Device, I want an unsupported or safely unexecutable command rejected with a stable reason, so that the backend can reconcile failure without leaking internals.
28. As a Child using a Child Device, I want a newer replaceable intent to supersede an older pending intent of the same kind, so that obsolete work is not applied later.
29. As a Child using a Child Device, I want unrelated commands to remain independent, so that superseding policy reconciliation does not cancel other future command families.
30. As a Child using a Child Device, I want an `apply_policy_version` command to reference a Supervision Policy version, so that authoritative policy data remains in the existing policy model.
31. As a Child using a Child Device, I want Child Mode to fetch the referenced policy before applying it, so that a command does not duplicate policy contents.
32. As a Child using a Child Device, I want policy-command acknowledgement to occur only after the referenced policy is locally applied, so that command completion matches effective behavior.
33. As a Child using a Child Device, I want the existing Policy Application State acknowledgement to complete the corresponding policy command idempotently, so that the two flows cannot disagree.
34. As a Child using a Child Device, I want initial enrollment to create an `apply_policy_version` command for version 1, so that initial and later policy reconciliation use one command model.
35. As a Child using a Child Device, I want an existing version-1 policy acknowledgement to complete the initial command safely, so that retries or ordering differences do not leave false pending work.
36. As a Child using a Child Device, I want an unprocessed policy command to expire after seven days, so that obsolete pending work does not live forever.
37. As a developer, I want FCM tokens stored separately from Guardian Device and Child Device identity, so that token rotation never changes authorization identity.
38. As a developer, I want one active FCM token to belong to exactly one delivery owner within the development environment, so that restored or reused tokens cannot address stale owners.
39. As a developer, I want registration to atomically invalidate a previous binding for the same token hash before activating the authenticated owner binding, so that reassignment has no duplicate-owner window.
40. As a developer, I want token plaintext encrypted with a versioned keyring and token lookup performed by hash, so that stored delivery credentials can rotate without being exposed.
41. As a developer, I want Guardian token registration authorized through the established Guardian actor boundary, so that a token cannot be assigned to another Guardian Device.
42. As a developer, I want Child token registration authorized through the established Child Device actor boundary, so that a token cannot be assigned to another Child Device.
43. As a developer, I want Guardian token registration after successful bootstrap and retried on startup, resume, and Firebase token change, so that active Guardian Devices converge on current delivery state.
44. As a developer, I want Child token registration verified and retried through Child Mode's existing enrollment and supervision lifecycle, so that delivery state survives transient failure.
45. As a developer, I want Guardian sign-out or Guardian Device revocation to revoke its token bindings, so that an inactive session stops receiving pushes.
46. As a developer, I want Replace Child Device, End Supervision, or Child Device revocation to revoke its token bindings, so that a retired Child Device stops receiving commands.
47. As a developer, I want all existing development Child token rows invalidated and re-registered without changing device identity or enrollment, so that the old storage shape is retired safely.
48. As a developer, I want permanent FCM invalid-token responses to mark only the affected token invalid, so that delivery does not repeatedly target a dead registration.
49. As a developer, I want transient FCM failures retried at most five times over approximately 15 minutes with jitter, so that brief outages recover without an unbounded retry loop.
50. As a developer, I want successful FCM acceptance treated only as wake-up delivery evidence, so that it never acknowledges a Guardian Notice or Child Device Command.
51. As a developer, I want Tamper Alert pushes sent with high priority, so that urgent degradation has the best available wake-up behavior.
52. As a developer, I want Offline, Recovery, and policy-reconciliation pushes sent with normal priority, so that routine delivery balances timeliness and battery use.
53. As a developer, I want FCM payloads limited to an opaque record identifier, schema version, and generic category, so that transport remains non-sensitive and forward-compatible.
54. As a developer, I want focused paginated reconciliation APIs with a maximum page size of 50, so that clients can recover bounded batches without broad table reads.
55. As a developer, I want acknowledgement, rejection, supersession, cancellation, and expiry represented explicitly, so that command lifecycle is inspectable and deterministic.
56. As a developer, I want notice receipts created per intended Guardian Device target, so that reconciliation accurately models the active-device set at notice creation.
57. As a developer, I want a Guardian receipt acknowledged only after the authoritative notice is committed to local cache and a presentation decision is persisted, so that process death cannot lose fetched work.
58. As a developer, I want terminal messaging records and health notices cleaned up daily in bounded batches after one week, so that storage remains controlled without large transactions.
59. As a Cereveil operator, I want FCM sending isolated in a private Convex Node action using a least-privilege service account, so that server credentials are not exposed to clients.
60. As a Cereveil operator, I want delivery outcomes observable without logging token plaintext, notification content, policy data, or sensitive Child data, so that failures can be diagnosed safely.
61. As a Cereveil operator, I want this implementation confined to the configured development Firebase project, so that unfinished behavior cannot reach production users.
62. As a tester, I want an end-to-end development smoke test across both Android registrations, Convex, and FCM, so that real credentials and wake-up routing are verified in addition to automated tests.

## Implementation Decisions

- Implement two independent authoritative infrastructures: Guardian Notices with per-Guardian-Device receipts, and Child Device Commands with per-command lifecycle. Do not model either as FCM messages.
- Follow the existing decisions that each active FCM token has exactly one delivery owner within an environment and that messaging work is acknowledged per target.
- Keep FCM token records separate from Guardian Device, Child Device, Child Device Credential, Active Enrollment, and Policy Application State records.
- Use the shared `fcmTokens` concept for both roles, with an owner kind and owner identity, environment, hash, encrypted token, lifecycle timestamps, and active/revoked/invalid status.
- Registration must hash the token for equality lookup, encrypt plaintext with the shared versioned keyring, invalidate another active binding with the same token hash, and then activate the authenticated owner's binding atomically.
- Never transfer, replace, or infer device identity from an FCM token.
- Invalidate existing development Child token rows and require normal client re-registration. Preserve Child Device identity, Active Enrollment, Role Lock, and Child Device Credential state.
- Guardian Mode registers its token after authenticated bootstrap, retries registration on application startup and resume, and registers rotations reported by Firebase. Registration does not depend on notification permission.
- Child Mode retains Firebase token-change handling and adds reliable pending-registration retry to startup and recurring supervision reconciliation.
- Revoke relevant bindings during sign-out, Guardian Device revocation, Replace Child Device, End Supervision, Child Device revocation, or other established delivery-owner deactivation paths.
- Store Guardian Notices as authoritative Household-scoped records with a concrete notice type, subject Child Profile where applicable, safe structured payload, occurrence time, deduplication identity, lifecycle information, and retention deadline.
- The initial Guardian Notice types are Offline Notice, Recovery Notice, and Tamper Alert. Existing Safety Alert and Access Request terminology may use the infrastructure later but is not implemented here.
- At notice creation, create a receipt for every active Guardian Device currently targeted. Each receipt has independent pending/processed presentation state and delivery-attempt information.
- A newly activated Guardian Device may query retained Guardian Notice history but begins with an installation baseline so retained records are not replayed as new system notifications.
- Guardian reconciliation returns authoritative notices and that Guardian Device's receipt state in pages of at most 50, ordered deterministically for resumption.
- Guardian processing commits the notice to local cache, decides whether a system notification should be shown, persists that decision, and only then acknowledges the receipt. Permission denial is a valid no-presentation decision and still permits acknowledgement.
- System notification identity must be stable per Guardian Notice and Guardian Device so repeated reconciliation cannot present more than once.
- Store Child Device Commands as authoritative records targeted to one active Child Device/Active Enrollment, with type, safe type-specific input reference, lifecycle status, idempotency identity, replaceable-intent key where applicable, timestamps, expiry, acknowledgement, and safe rejection reason.
- Command lifecycle states are pending, acknowledged, rejected, superseded, cancelled, and expired.
- Fetching a command does not change it to acknowledged. FCM acceptance and local receipt also do not acknowledge it.
- Clients process commands independently. A failed or pending command does not impose a global ordered queue over unrelated commands.
- Creation is idempotent by a server-owned intent key. Repeated creation of the same intent returns or preserves the same effective command rather than duplicating work.
- A newly created replaceable intent supersedes older pending commands with the same target and replaceable-intent key. It does not supersede unrelated command types.
- The only initial command type is `apply_policy_version`, targeted to the active enrollment and referencing `childProfileId` plus the desired Supervision Policy version or equivalent authoritative reference. It does not embed policy contents.
- Policy version numbers belong to Supervision Policy evolution, not to a global Child Device Command sequence. A policy version 9 command may supersede a pending policy version 8 command because both represent the same replaceable policy-reconciliation intent.
- Creating initial desired Policy Application State during enrollment also creates `apply_policy_version(1)`. A successful existing policy acknowledgement idempotently acknowledges the matching command.
- Later desired-policy changes create or preserve a command for the new desired version and supersede an older pending policy-reconciliation command.
- Child Mode fetches the command, fetches the referenced authoritative policy through the existing policy boundary, applies it locally, persists local application state, and acknowledges through the existing policy acknowledgement contract extended to reconcile the command.
- Acknowledgement of an already completed matching command is idempotent. A stale/superseded version must not overwrite newer desired or applied policy state.
- Use a small stable rejection-reason enum for safe terminal client outcomes such as unsupported command, invalid command data, or permanently unable to apply. Do not return or persist raw exception text as a reason.
- Pending `apply_policy_version` commands expire after seven days. Terminal command records, Guardian receipts, and health notices are retained for seven days before bounded cleanup.
- Add focused authenticated queries and mutations for Guardian reconciliation/acknowledgement and Child command fetch/acknowledgement/rejection. Preserve the established Guardian and Child actor wrappers and their authorization semantics.
- Maximum reconciliation page size is 50. Pagination uses stable server ordering and continuation state; clients repeat until caught up.
- FCM uses Android data-only messages. Payloads contain only a schema version, opaque authoritative record identifier, and generic category such as guardian notice or child command.
- Guardian wake-up metadata does not include Child name, health condition, capabilities, notice text, or other presentation content.
- Child wake-up metadata does not include command type, policy version, policy content, or action parameters.
- FCM remains advisory. Both roles reconcile after a push and on application startup/resume; Child Mode also uses the existing periodic supervision worker. Guardian Mode does not add periodic background polling in this slice.
- Implement FCM sending as a private Convex Node action that decrypts active tokens and calls the FCM HTTP v1 API using the configured development service account.
- Keep all service-account credentials and encryption keys in Convex development environment variables. Android Firebase client configuration is non-secret and remains development-flavor configuration.
- Treat unregistered/invalid-argument responses that definitively identify an unusable registration as permanent and mark that token invalid. Do not invalidate on ambiguous or transient responses.
- Retry transient delivery failure at most five attempts over approximately 15 minutes using exponential or scheduled backoff with jitter. Persist sufficient delivery state for idempotent retry.
- Use high-priority delivery for Tamper Alert wake-ups. Use normal priority for Offline Notice, Recovery Notice, and `apply_policy_version` commands.
- An accepted FCM response records a delivery-attempt outcome only. It does not transition Guardian receipt processing or Child command acknowledgement.
- Create an Offline Notice exactly when the authoritative guarded 45-minute transition changes connectivity to Offline. Repeated/stale checks for the same outage do not duplicate it.
- Model an Offline episode with a stable correlation/deduplication identity. A later accepted heartbeat changing that same enrollment from Offline to Online creates exactly one correlated Recovery Notice and closes the episode.
- Do not create a Recovery Notice for initial Pending-to-Online or Online-to-Online heartbeat processing.
- Compare each accepted heartbeat capability snapshot with the last reported snapshot. Create one Tamper Alert for the set of capabilities newly changing from available to unavailable.
- The first degraded heartbeat creates a Tamper Alert for all unavailable required capabilities because they are newly reported unavailable relative to pending/expected Protection Setup availability.
- Do not create another Tamper Alert while a capability remains unavailable. When it becomes available, silently resolve that degradation; if it later becomes unavailable again, the new transition may create another alert.
- Connectivity loss alone never creates a Tamper Alert and must not change Offline Notice language into a tamper allegation.
- Process connectivity recovery and capability degradation independently so one heartbeat may create both Recovery Notice and Tamper Alert.
- Run cleanup daily, querying indexed retention/lifecycle fields and deleting bounded pages so one cleanup execution cannot scan or mutate the entire messaging dataset.
- Add safe operational outcomes for delivery attempts, retry exhaustion, invalid tokens, and reconciliation while excluding FCM plaintext tokens, encrypted token values, service credentials, Child identity, policy content, and capability payloads from logs.
- Development Firebase is the only configured environment for this PRD. No production Firebase project, production Android registration, or production sender credential is created.

## Testing Decisions

- Test externally observable authorization, lifecycle, deduplication, reconciliation, delivery classification, and client behavior rather than private helper functions, database row layout incidental to the contract, coroutine structure, or exact scheduler timing.
- Use the authenticated Convex public boundaries as the primary backend seam: Guardian queries/mutations through the established Guardian wrapper and Child requests through the established Child HTTP wrapper. This follows the existing enrollment, policy, heartbeat, and wrapper-hardening test style.
- Inject one fake FCM gateway behind the private delivery action boundary. Backend integration tests assert requested non-sensitive payload semantics and persisted delivery outcomes without calling Firebase or mocking low-level HTTP internals throughout the codebase.
- Backend tests cover token registration, encrypted storage, hashing, rotation, atomic same-token reassignment, owner isolation, environment isolation, revocation, invalidation, and migration/re-registration of existing development Child rows.
- Backend tests cover Guardian authorization, Child authorization, foreign-owner rejection, revoked-device rejection, and the rule that token registration cannot alter device identity.
- Backend tests cover Guardian Notice creation and one receipt per active Guardian Device, including two-device independence and no receipt for revoked devices.
- Backend tests cover deterministic pagination with a maximum of 50, catch-up across multiple pages, idempotent acknowledgement, and newly activated Guardian Device history without old system-notification replay semantics.
- Backend tests cover command creation idempotency, independent lifecycle, safe rejection, cancellation, expiry, acknowledgement, and supersession only within the same replaceable intent.
- Backend tests cover enrollment creating `apply_policy_version(1)`, existing policy acknowledgement completing it, later policy versions superseding older pending reconciliation, and stale acknowledgement never overwriting newer state.
- Backend tests cover that FCM acceptance never processes a Guardian receipt or acknowledges a Child command.
- Fake-gateway tests cover successful delivery, permanent invalid-token classification, transient retry scheduling, five-attempt exhaustion, jitter within safe bounds, and one token failure not blocking other target tokens.
- Fake-gateway tests inspect payloads and prove Guardian messages omit notice content and Child identity, while Child messages omit command type, policy version, and policy data.
- Backend tests cover Tamper Alert high priority and Offline, Recovery, and policy-command normal priority.
- Extend existing Supervision Heartbeat integration tests to cover exactly-once Offline Notice creation at the guarded transition, no duplicate notice from stale checks, correlated exactly-once recovery, no recovery from Pending-to-Online, first-degraded-heartbeat Tamper Alert, grouped newly unavailable capabilities, persistent-degradation deduplication, silent capability recovery, re-degradation, and simultaneous Recovery plus Tamper.
- Backend tests explicitly prove that Offline without capability evidence never creates or describes a Tamper Alert.
- Backend tests use controlled time/scheduler execution rather than sleeping for 45 minutes, seven days, or retry intervals.
- Android Guardian tests use a fake authenticated messaging repository, local notice store, presentation decision boundary, and token provider at the coordinator/ViewModel level. This follows existing Guardian bootstrap and Child Profile coordinator test patterns.
- Guardian client tests cover bootstrap-before-registration, startup/resume retry, token rotation, sign-out revocation request, reconcile-after-push, multi-page catch-up, cache-before-acknowledgement, permission-denied acknowledgement, stable one-time system presentation, and process-restart recovery.
- Android Child tests extend the existing enrollment/supervision coordinator seams with a fake command repository. They cover registration retry, token refresh, reconcile-after-push, periodic fallback, independent command processing, duplicate fetch, apply-policy fetch/apply/persist/ack order, stale superseded commands, safe rejection, and transient retry.
- Android tests do not assert Firebase SDK internals or WorkManager implementation details when an application-level coordinator seam expresses the behavior.
- Add a documented manual smoke test against the real development Firebase project using one Guardian development build and one Child development build. Verify both registrations, Guardian Notice wake-up and fetch, Child command wake-up and fetch, invalid-token retirement where practical, startup/resume reconciliation with a deliberately missed push, and absence of sensitive payload fields.
- The real Firebase smoke test supplements automated tests and is not required to run in the default unit-test suite.

## Out of Scope

- Production Firebase project creation, production Android app registration, production credentials, production rollout, or migration of real production tokens.
- Child Device Command types other than `apply_policy_version`, including Live Location Session, Remote Audio Session, Access Grant, app actions, or arbitrary remote execution.
- Treating the entire Supervision Policy as a Child Device Command or embedding authoritative policy contents in a command.
- Safety Alert and Access Request creation or UI, even though the Guardian Notice infrastructure should be capable of supporting them later.
- A capability-restored Guardian Notice.
- Inferring tampering from Offline state, network loss, missing FCM delivery, or an unexplained stopped heartbeat.
- Guardian periodic background polling; Guardian reconciliation is limited to FCM, startup/resume, and existing live Convex behavior where applicable.
- Exact delivery guarantees from Android or FCM, lock-screen notification content sourced directly from FCM, or using FCM acceptance as authoritative acknowledgement.
- Live location collection, Location Heartbeat changes, continuous tracking, or implementation of a Live Location Session command.
- Changes to the meaning of Supervision Policy, Policy Application State, Supervision Heartbeat, Active Enrollment, or device authorization beyond the focused integration described here.
- A general-purpose workflow engine, global command sequence, cross-command transactional ordering, or arbitrary plugin command registry.
- Historical notice retention beyond one week, analytics over delivery history, notification preference controls, quiet hours, escalation policies, or email/SMS fallback.
- Immediate capability event reporting outside the existing Supervision Heartbeat cadence.

## Further Notes

- The development Firebase project and both development Android registrations are already configured. The FCM HTTP v1 API is enabled, and a least-privilege sender service account plus versioned token-encryption configuration are installed in the Convex development environment.
- Guardian and Child application Firebase identifiers are already exposed through development build configuration. Firebase server credentials must never be added to source control or Android resources.
- Existing Child token registration and retry behavior is partial prior art, not the final contract. Preserve useful client lifecycle behavior while replacing old development token rows through re-registration.
- The authoritative-messaging model deliberately mirrors the reliability lesson of Policy Application State—desired backend state and explicit device acknowledgement—without claiming every command is Supervision Policy. `apply_policy_version` is one command type that reconciles the independent policy model.
- ADR 0070 governs exclusive FCM token ownership. ADR 0071 governs per-target messaging acknowledgement. Implementations that use shared Guardian cursors, acknowledge on fetch/FCM acceptance, or attach token identity directly to device identity would conflict with those decisions.
