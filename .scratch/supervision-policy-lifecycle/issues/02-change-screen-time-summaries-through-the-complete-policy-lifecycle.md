Status: ready-for-agent

# Change Screen Time Summaries through the complete policy lifecycle

## Parent

[Supervision Policy Lifecycle Foundation](../PRD.md) — user stories 1–17, 22–31, and 54–68.

## What to build

Deliver the first Guardian-driven policy-change tracer bullet using the Screen Time Summaries boolean section. An authorized Guardian with an Active Enrollment must be able to read desired and applied policy snapshots and request a typed Screen Time Summaries change. Convex must construct the complete next snapshot, enforce optimistic concurrency and retry identity, avoid no-op versions, supersede the previous active policy, advance desired Policy Application State, and create the existing replaceable `apply_policy_version` command transactionally.

Add a development-only Guardian control that responds immediately with optimistic inline progress but derives its final toggle value from authoritative Child acknowledgement. The Child must reconcile, atomically accept, persist, and acknowledge the new complete policy through the typed seam established by issue 01. This is configuration-lifecycle scaffolding; Screen Time Summary collection and upload remain out of scope.

## Acceptance criteria

- [x] A focused authorized Guardian query returns the complete desired policy, the acknowledged applied policy when available, application status, and version metadata required for safe updates without returning unrelated Household data.
- [x] An Unenrolled Child Profile, wrong Household, disabled Guardian Account, or invalid Active Enrollment cannot read or change enrolled policy state through this flow.
- [x] A feature-specific Screen Time Summaries mutation accepts the desired typed section, expected current version, and a stable Save-operation identifier; it never accepts a client-composed whole policy.
- [x] One shared policy update use case validates authority, lifecycle, schema compatibility, expected version, operation identity, and the resulting complete snapshot.
- [x] An effective change creates exactly one next immutable policy version, marks only the previous active version superseded, retains history, sets the new version desired and pending, and creates one effective reconciliation command in the same transaction.
- [x] An unchanged requested value succeeds as a no-op without creating a version, changing application state, creating a command, scheduling delivery, or implying timestamp churn from an effective change.
- [x] Retrying the same operation identifier with identical content returns the original result; reusing it with different content is rejected safely.
- [x] Two Guardian Devices saving from the same base version cannot overwrite one another: exactly one distinct operation succeeds and the other receives a stable stale-policy conflict.
- [x] A newer desired version supersedes only older pending `apply_policy_version` work for the same Active Enrollment; unrelated command families remain unaffected.
- [x] Child Mode fetches, atomically accepts, persists, and acknowledges the new complete policy, after which desired and applied snapshots converge and the matching command is acknowledged.
- [x] The development Guardian control shows immediate inline progress, changes to Waiting for Child Device after a bounded UI wait, survives navigation/recreation from authoritative state, and renders its final on/off value only from the applied snapshot.
- [x] A rejected Guardian save rolls back optimistic presentation to authoritative state, and stale conflict reloads the latest policy rather than silently merging.
- [x] Backend, Android Child, Guardian view-model, and Compose tests cover the full save-to-acknowledgement path through their public behavioral seams.

## Blocked by

- [01 — Apply a typed schema-v1 Initial Supervision Policy end to end](01-apply-a-typed-schema-v1-initial-policy-end-to-end.md)
