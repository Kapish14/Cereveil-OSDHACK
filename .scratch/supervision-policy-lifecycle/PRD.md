Status: ready-for-agent

# Supervision Policy Lifecycle Foundation

## Problem Statement

Cereveil has the beginning of a versioned Supervision Policy model and a complete `apply_policy_version` Child Device Command lifecycle, but Guardian Mode cannot yet change supervision settings after enrollment. Convex creates only the privacy-default Initial Supervision Policy, Policy Application State is initialized only during enrollment, and Guardian Mode receives only a coarse pending/applied status. There is no Guardian-facing current-policy query, feature-specific update contract, immutable version-creation workflow for later edits, concurrency protection, retry identity, policy-schema compatibility contract, or permanent application-failure state.

Child Mode can fetch, locally store, activate, and acknowledge a referenced policy, but it currently treats raw JSON as its model and persists a fetched policy before activation succeeds. That ordering can replace the last accepted offline policy with a candidate that was never successfully activated. The current Android runtime stores development configuration flags but the actual App Blocking, Safe Browsing, Active Screen Safety, and Screen Time Summary engines are not yet implemented.

Without this foundation, future features would each invent their own update, versioning, reconciliation, compatibility, and UI-progress behavior. Concurrent Guardian Devices could overwrite one another, uncertain network retries could create ambiguous saves, unsupported Child app versions could receive policies they cannot interpret, and Guardian Mode could claim that a setting is active before the Child Device acknowledges it.

## Solution

Complete the generic Supervision Policy lifecycle around strongly typed, schema-versioned, immutable policy snapshots. Guardian Mode will read authorized desired and applied policy state and change one typed feature section at a time. Convex will own construction and validation of the resulting complete snapshot, reject stale concurrent saves, make deliberate Save actions safely retryable, avoid versions for no-op changes, create the next immutable version, supersede the previous active version, update Policy Application State, and use the existing replaceable `apply_policy_version` command and FCM wake-up infrastructure.

Child Mode will report its supported policy schema, parse fetched policies into typed models, validate and activate complete snapshots atomically, preserve the previous last accepted policy on failure, and acknowledge only the current desired version after successful activation. Policy Application State will distinguish pending, applied, and permanently failed outcomes. Transient delivery or activation failures will remain pending and retry.

Add a development-only Guardian policy screen for the four sections already represented by the model: App Blocking, Safe Browsing and Safe Search Enforcement, Active Screen Safety, and Screen Time Summaries. A tap will show immediate optimistic inline progress, while the final toggle state will come from authoritative Child acknowledgement observed through Convex. Long waits will become an inline Waiting for Child Device state, and permanent rejection will become an inline Could not apply state. Actual enforcement engines remain future feature work.

## User Stories

1. As a Guardian, I want to configure supervision through recognizable feature controls, so that I do not need to understand policy records or commands.
2. As a Guardian, I want policy controls available only for an enrolled Child Profile, so that settings are not presented before a Child Device can receive them.
3. As a Guardian, I want the backend to reject policy changes for an Unenrolled Child Profile, so that hidden UI is not the only lifecycle protection.
4. As a Guardian, I want the Initial Supervision Policy to remain the privacy-safe baseline before enrollment, so that optional supervision features do not start implicitly.
5. As a Guardian, I want each feature screen to change only its own settings, so that changing Safe Browsing cannot accidentally overwrite App Blocking or another feature.
6. As a Guardian, I want one deliberate feature change to create one coherent desired policy, so that the Child Device never receives an accidental mixture of settings.
7. As a Guardian, I want a tap to produce immediate inline progress, so that the app feels responsive while the request is processed.
8. As a Guardian, I want a toggle to show its final on or off state only after the Child Device acknowledges the policy, so that saved intent is not mistaken for active configuration.
9. As a Guardian, I want a short pending operation to use an inline spinner, so that I can see that my tap is being processed.
10. As a Guardian, I want a long pending operation to say Waiting for Child Device, so that an Offline Child Device does not make the app look frozen.
11. As a Guardian, I want pending state to survive navigation and app restart, so that reopening the screen shows the authoritative situation.
12. As a Guardian, I want pending state to be consistent on both active Guardian Devices, so that progress does not depend on which phone made the change.
13. As a Guardian, I want an Offline Child Device to keep its last accepted policy, so that a pending change does not weaken known offline enforcement.
14. As a Guardian, I want a saved change to apply after the Child Device reconnects, so that I do not need to repeat it after an Offline period.
15. As a Guardian, I want a permanent application rejection to stop showing indefinite progress, so that I know the desired setting did not become active.
16. As a Guardian, I want permanent failure shown inline as Could not apply, so that the control communicates failure without requiring a separate status dashboard.
17. As a Guardian, I want a later valid change to recover from a failed desired version, so that a prior rejection does not permanently block policy management.
18. As a Guardian, I want inconsistent feature settings prevented by the UI, so that dependent controls are understandable.
19. As a Guardian, I want Convex to reject inconsistent settings even if a client submits them, so that policy correctness does not depend on UI behavior.
20. As a Guardian, I want Safe Search Enforcement unavailable while Safe Browsing is disabled, so that the configuration reflects how enforcement works.
21. As a Guardian, I want turning off Safe Browsing to turn off Safe Search Enforcement in the same deliberate feature update, so that the saved section remains coherent.
22. As a Guardian, I want an unchanged update to return the current policy without creating another version, so that repeated taps or stale UI do not create meaningless history.
23. As a Guardian, I want automatic retries of the same Save action to return the same result, so that a lost response cannot create duplicate policy versions.
24. As a Guardian, I want a successful save to survive a network response being lost, so that retrying does not falsely appear as another person's conflicting edit.
25. As a Guardian, I want a stale edit rejected when another Guardian Device has already saved a newer version, so that one device cannot silently overwrite the other.
26. As a Guardian, I want stale-save rejection to reload the latest policy, so that I can review current settings before trying again.
27. As a Guardian, I do not want stale settings silently merged, so that every policy version remains a deliberate complete configuration.
28. As a Guardian, I want unrelated feature controls to remain editable while another feature is pending, so that an Offline Child Device does not block all configuration.
29. As a Guardian, I want a later unrelated change built from the latest desired policy, so that it preserves earlier pending intent.
30. As a Guardian, I want the same feature control disabled while its own change is pending, so that repeated contradictory changes are not ambiguous.
31. As a Guardian, I want a newer desired policy to supersede older pending policy-reconciliation work, so that the Child applies the latest complete intent.
32. As a Guardian, I want the Child app updated before enabling a policy schema it cannot understand, so that unsupported settings are not left permanently pending.
33. As a Guardian, I want an understandable update-required result when Child Mode is too old, so that compatibility failure is not presented as a generic network error.
34. As a Guardian, I want feature controls hidden behind a development-only surface until their actual enforcement engines are ready, so that unfinished controls do not appear in a production experience.
35. As a Child using a Child Device, I want policy content fetched from the authoritative policy endpoint rather than embedded in a command, so that commands remain bounded references.
36. As a Child using a Child Device, I want policies parsed into explicit typed models, so that malformed feature data is rejected before activation.
37. As a Child using a Child Device, I want unknown policy schemas rejected safely, so that Child Mode never guesses how to enforce unsupported configuration.
38. As a Child using a Child Device, I want my supported policy schema reported during enrollment, so that the initial desired policy is compatible.
39. As a Child using a Child Device, I want supported policy schema reported during authenticated health reporting, so that Convex learns compatibility after app updates.
40. As a Child using a Child Device, I want the complete policy validated before activation, so that one invalid section cannot produce partial enforcement.
41. As a Child using a Child Device, I want all required feature sections activated before acknowledgement, so that one applied version has one truthful meaning.
42. As a Child using a Child Device, I want the last accepted policy persisted only after successful activation, so that offline enforcement never points at an unactivated candidate.
43. As a Child using a Child Device, I want a downloaded candidate kept distinct from the last accepted policy if it is persisted for retry, so that process death cannot corrupt accepted state.
44. As a Child using a Child Device, I want the previous accepted policy retained when a candidate fails, so that failed updates do not remove known configuration.
45. As a Child using a Child Device, I want transient activation failures retried, so that temporary Android conditions do not become permanent rejection immediately.
46. As a Child using a Child Device, I want permanent invalid or unsupported policies rejected with a safe stable reason, so that the backend can stop waiting without receiving implementation details.
47. As a Child using a Child Device, I want a stale desired version prevented from advancing applied state, so that delayed work cannot overwrite newer Guardian intent.
48. As a Child using a Child Device, I want duplicate command fetches and acknowledgements to remain idempotent, so that reconciliation is safe under retry.
49. As a Child using a Child Device, I want the matching policy command completed by successful policy acknowledgement, so that command and Policy Application State cannot disagree.
50. As a developer, I want each policy snapshot to declare a schema version, so that policy representation can evolve deliberately.
51. As a developer, I want every feature section strongly typed and validated, so that arbitrary feature names or JSON cannot enter authoritative policy state.
52. As a developer, I want older policy versions left immutable when a new schema is introduced, so that history remains truthful.
53. As a developer, I want future features added as explicit typed sections, so that extensibility does not weaken validation.
54. As a developer, I want a complete immutable snapshot stored for every effective Guardian change, so that any version can be fetched and understood independently.
55. As a developer, I want feature-specific public mutations backed by one shared policy-versioning service, so that features do not duplicate lifecycle logic.
56. As a developer, I want policy version construction performed in Convex, so that feature clients do not need to know or resubmit unrelated sections.
57. As a developer, I want the update transaction to verify Guardian authority and Active Enrollment state, so that ownership and lifecycle cannot be spoofed.
58. As a developer, I want the update transaction to compare the expected current version, so that concurrent Guardian saves use optimistic concurrency.
59. As a developer, I want each deliberate Save action identified by a stable operation identifier, so that uncertain network retries are distinguishable from new edits.
60. As a developer, I want reuse of an operation identifier with different content rejected, so that idempotency identity cannot alias two actions.
61. As a developer, I want successful replay of an operation return its previously created policy version, so that retry behavior is deterministic.
62. As a developer, I want rejected, conflicted, and no-op updates to avoid consuming a policy version number, so that version history represents effective changes.
63. As a developer, I want insertion of the new policy, supersession of the prior policy, desired-state update, and command intent creation to be one Convex transaction, so that authoritative records cannot disagree.
64. As a developer, I want command delivery scheduled after authoritative state exists, so that FCM remains a recoverable wake-up rather than a source of truth.
65. As a developer, I want only older pending policy-reconciliation commands superseded, so that unrelated future command families remain independent.
66. As a developer, I want Guardian policy reads focused and authorized, so that broad Household data is not returned with a settings screen.
67. As a developer, I want Guardian policy state to include desired and applied snapshots plus application status, so that UI state can be reconstructed authoritatively.
68. As a developer, I want version metadata returned even when the ordinary UI hides it, so that the next update can perform concurrency checks.
69. As a developer, I want Policy Application State to represent pending, applied, and failed outcomes, so that permanent rejection is not confused with an Offline wait.
70. As a developer, I want failure records limited to allowlisted machine-readable reasons, so that policy data and internal exceptions are not exposed.
71. As a developer, I want supported schema compatibility enforced in Convex as well as Child Mode, so that a modified Guardian client cannot create unsupported desired state.
72. As a developer, I want all superseded policies retained until End Supervision, so that desired/applied history remains diagnosable during the Child Profile lifetime.
73. As a developer, I want End Supervision to continue deleting all policy versions and Policy Application State, so that this history does not outlive the Child Profile.
74. As a developer, I want the development schema to make policy schema version explicit without migration compatibility machinery, so that pre-release implementation stays clean.
75. As a developer, I want existing policy and command tests extended rather than replaced, so that later policy updates preserve the already completed messaging guarantees.
76. As a tester, I want to verify the complete Guardian-save-to-Child-acknowledgement path at public boundaries, so that helper-level success cannot hide integration failures.
77. As a tester, I want controlled concurrent saves from two Guardian actors/devices, so that exactly one stale base version wins.
78. As a tester, I want simulated lost responses and repeated operation identifiers, so that retry idempotency is demonstrated.
79. As a tester, I want Child process-death and activation-failure scenarios, so that last accepted offline policy ordering is demonstrated.
80. As a tester, I want the development policy UI exercised through loading, pending, waiting, applied, conflict, unsupported, and failed states, so that authoritative state is presented consistently.

## Implementation Decisions

- Preserve the existing domain separation: Supervision Policy is durable configuration, Policy Application State tracks desired versus applied state, Child Device Command requests reconciliation, and FCM is only a wake-up signal.
- Extend the existing policy domain module rather than placing policy creation logic in the command or Child Profile modules.
- Store every effective policy version as a complete immutable snapshot and retain superseded versions until End Supervision.
- Add a required integer policy schema version. Because the product is pre-release and has no users, make a clean development schema change and update fixtures rather than adding production migration compatibility machinery.
- Keep feature sections explicitly typed and validated. Do not store a dynamic feature-name-to-arbitrary-JSON map.
- Start with the four existing policy areas: App Blocking, Safe Browsing with Safe Search Enforcement, Active Screen Safety, and Screen Time Summaries.
- Treat the existing Initial Supervision Policy as schema version 1 with privacy-safe defaults.
- Do not allow Guardian policy updates before Active Enrollment. Enrollment continues to initialize desired state from the latest active Initial Supervision Policy and creates the matching initial command.
- Expose a focused Guardian policy-state query returning the desired complete policy, the acknowledged applied complete policy when available, application state, safe failure reason when applicable, and version metadata required for updates.
- Do not expose a separate ordinary-user version dashboard. Version metadata is an API/concurrency concern and may be visible only in development diagnostics.
- Expose feature-specific Guardian update operations rather than one client-supplied whole-policy replacement operation.
- Back every feature-specific operation with one shared policy update use case that loads the current complete snapshot, applies one validated typed section, validates cross-field invariants, and constructs the next snapshot.
- Require `expectedCurrentVersion` on each update. Reject a different operation based on a stale version with a stable policy-conflict error; do not silently merge or overwrite.
- Require a stable client-generated operation identifier for each deliberate Save action and reuse it for automatic retries.
- Store enough server-owned operation identity and request fingerprint information to return the previously created policy for an identical retry and reject reuse with different content.
- Treat an update that produces no policy difference as a successful no-op returning the current version, without creating a version, changing application state, creating a command, or sending FCM.
- Perform authority validation, Active Enrollment validation, compatibility validation, version comparison, idempotency handling, previous-version supersession, new-version insertion, Policy Application State update, and command-intent creation transactionally.
- Use Convex transaction conflicts together with explicit expected-version checks to prevent lost updates from two equal-authority Guardian Devices.
- Set a new effective policy as desired and Policy Application State as pending. Clear any safe failure reason from the previous desired version.
- Reuse the existing idempotent, replaceable `apply_policy_version` command creation path. A new desired version supersedes only older pending policy-reconciliation commands for the same Active Enrollment.
- Continue scheduling minimal FCM wake-up delivery after authoritative command creation. Successful FCM delivery never changes applied state.
- Add `failed` to Policy Application State for permanent Child rejection. Retain `pending` for Offline, delivery, and retryable activation conditions; retain `applied` only when desired and applied versions match.
- Store only allowlisted non-sensitive application failure codes. Do not store raw Android exceptions, policy content, or device details in failure fields.
- Add supported-policy-schema metadata to Active Enrollment-associated Child state or health state at the appropriate churn boundary.
- Require Child Mode to report its maximum supported schema during enrollment and subsequent authenticated health reporting so app updates can advance compatibility.
- Reject a Guardian update whose resulting policy schema exceeds the Active Enrollment's reported support. Child Mode independently rejects unsupported schemas as defense in depth.
- Replace raw-JSON-only Android policy handling with explicit Kotlin policy and feature-section models while preserving strict parsing and schema checks.
- Introduce one high-level Child policy application seam that accepts a complete typed candidate and returns success, retryable failure, or safe permanent rejection.
- Validate and prepare the complete candidate before committing it as accepted. Acknowledgement occurs only after every required section reports successful activation.
- Persist the last accepted policy only after successful activation. If a candidate is cached for retry, store it separately and never load it as the accepted offline policy.
- On candidate failure, preserve and continue using the previous accepted policy. Never report partial application of a policy version.
- Correct the existing Android ordering that saves fetched policy before runtime activation.
- Keep acknowledgement idempotent and require the acknowledged version to equal the current desired version. Successful acknowledgement updates applied state and completes the matching pending command in the same authoritative flow.
- Map permanent Child rejection of the active desired command into failed Policy Application State without allowing stale or superseded rejection to alter newer state.
- Add a development/debug-only Guardian policy screen. Do not expose incomplete feature controls in the normal production settings surface.
- Build reusable feature-control UI state around authoritative applied value, desired value, and application state rather than maintaining a separate client source of truth.
- On tap, show immediate optimistic inline progress. If the save is rejected, discard the optimistic state and restore the subscribed authoritative state.
- After a short wait, replace the spinner with an inline Waiting for Child Device message. On permanent failure, show inline Could not apply. Do not require a separate policy status bar.
- Render the final toggle state from the acknowledged applied policy. A successfully saved desired value alone does not make the control appear applied.
- Preserve pending presentation across navigation, process restart, and both Guardian Devices by reconstructing it from the focused Convex query.
- Disable another change to the same feature section while its desired value differs from applied state. Permit updates to unrelated feature sections, building them from the latest desired snapshot.
- Treat this slice as policy architecture and development scaffolding. Policy acknowledgement means the current development Child policy runtime accepted its configuration; production feature claims require their actual enforcement engines before release.
- Follow ADR-0054 and the newly recorded ADRs 0072 through 0076.

## Testing Decisions

- Prefer the highest existing behavioral seams: public Guardian Convex query/mutation boundaries, authenticated Child Device HTTP endpoints plus command reconciliation, the Android Child supervision coordinator/runtime boundary, and the Guardian view-model/Compose state boundary.
- Backend tests should use the existing Convex test harness and extend the current enrollment/policy/command integration coverage rather than asserting internal helper calls.
- Test authorized policy-state reads for applied, pending, failed, Unenrolled, wrong-Household, disabled-account, and missing-record cases.
- Test every feature-specific update through its public Guardian mutation, including valid change, invalid combination, no-op, unsupported schema, Unenrolled Child Profile, and authorization failure.
- Test that one update creates exactly one next immutable snapshot, supersedes exactly the prior active snapshot, retains older snapshots, updates desired state, and creates one effective command.
- Test that a no-op creates no version, state transition, command, delivery attempt, or timestamp churn that implies an effective change.
- Test identical operation replay before and after command delivery and acknowledgement, returning the original result without duplicated effective work.
- Test operation identifier reuse with different feature content is rejected safely.
- Test two Guardian Devices updating from the same expected version. Exactly one effective mutation succeeds; the other receives the stable stale-policy result and cannot overwrite it.
- Test an unrelated feature update while an older desired version is pending. The new complete snapshot preserves the earlier desired section and supersedes only the older pending policy command.
- Test Guardian reads return both desired and applied snapshots when versions differ, and the correct single applied state when they match.
- Test new desired state clears an older failure and that stale rejection cannot mark a newer desired version failed.
- Test schema support reported at enrollment and heartbeat, including advancement after a Child app update and rejection of policies above reported support.
- Test Child policy parsing rejects unknown schemas, missing required sections, wrong types, unknown unsupported structures, and internally inconsistent data without replacing accepted state.
- Test application ordering with a fake high-level policy runtime: validate/activate succeeds before accepted persistence and acknowledgement; retryable or permanent failure preserves the previous accepted policy and sends no acknowledgement.
- Test candidate recovery across process death so an unaccepted candidate is never loaded as the offline accepted policy.
- Test successful acknowledgement updates Policy Application State and command lifecycle consistently and remains idempotent under duplicate requests.
- Test stale policy acknowledgement and stale command rejection cannot overwrite newer desired, applied, or failed state.
- Extend Android Child coordinator tests for command supersession, schema rejection, retryable activation, permanent rejection, token refresh, startup reconciliation, periodic fallback, and duplicate fetch.
- Guardian Android tests should drive a fake authoritative policy stream and update client through the view-model boundary. Cover loading, immediate optimistic progress, short spinner, long Waiting for Child Device, applied acknowledgement, permanent failure, stale-save reload, unsupported schema, retry, navigation/recreation restoration, same-feature disabling, and unrelated-feature availability.
- Compose tests should assert visible behavior and enabled/disabled control state, not internal coroutine or mutable-state implementation.
- Keep one end-to-end development smoke path from a Guardian feature change through Convex version/command creation, Child fetch/application/acknowledgement, and subscribed Guardian toggle completion. FCM loss must not prevent eventual reconciliation through startup or periodic work.
- Tests must not assert actual App Blocking, VPN filtering, NSFW/Scam detection, or Screen Time collection behavior in this PRD; those engines have separate future acceptance tests.

## Out of Scope

- Actual App Blocking enforcement, app selection, Manual Blocks, Scheduled Blocks, Block Screen behavior, or Access Requests and Grants.
- Safe Browsing VPN or DNS implementation, Domain Rules, blocklists, and real Safe Search Enforcement.
- Active Screen Safety capture, Scam Text Detection, NSFW Screen Detection, Monitored App selection, Safety Warnings, Safety Alerts, or incident suppression.
- Screen Time Summary collection, aggregation, upload, deletion, retention, and Guardian analytics UI.
- Location policy design, Location Heartbeats, Live Location Sessions, or location permission behavior.
- Remote Audio Session policy or runtime behavior.
- Promoting development policy controls into normal production Guardian settings before their feature engines are complete.
- A generic arbitrary feature plugin system or unvalidated JSON policy sections.
- Automatic conflict merging between Guardian Devices.
- Pre-enrollment Guardian policy editing.
- A production data migration for existing policy rows; current development data may be reset after the clean schema change.
- A separate Guardian policy version-history screen or user-facing raw version numbers.
- Redesigning the established Child Device Command, FCM delivery, Guardian Notice, authentication, enrollment, or Supervision Health architectures beyond the compatibility and policy-state fields required here.

## Further Notes

- The existing `apply_policy_version` implementation is the delivery and reconciliation half of this feature. This PRD supplies the missing Guardian-driven policy creation half and strengthens Child acceptance semantics.
- “Authoritative acknowledgement” means Convex has accepted an acknowledgement from the authorized active Child Device for the current desired policy version after Child Mode successfully activated the complete snapshot. A Guardian mutation response or successful FCM delivery is not acknowledgement.
- The Cereveil App is one product, but Guardian and Child installations can run different releases because of staged rollout, Offline devices, automatic-update timing, storage, or network constraints. Policy-schema compatibility is therefore required even with one application package.
- Superseded policy history remains Child Profile data and is deleted by End Supervision under the existing lifecycle decision.
- The implementation should use Guardian, Guardian Mode, Child Mode, Supervision Policy, Policy Application State, Child Device Command, Active Enrollment, and last accepted policy consistently with the repository glossary.
