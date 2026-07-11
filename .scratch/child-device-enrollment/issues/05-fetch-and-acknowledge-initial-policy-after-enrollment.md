Status: ready-for-agent

# Fetch and Acknowledge Initial Policy after Enrollment

## Parent

.scratch/child-device-enrollment/PRD.md

## What to build

After Child Mode persists local enrollment state, fetch the current Supervision Policy through the normal authenticated child policy path, store it as the last accepted policy, begin only the policy-controlled behavior it enables, and acknowledge the applied version back to Convex.

This slice ensures Active Enrollment does not falsely mean policy is already applied, and avoids making enrollment completion a special policy delivery path.

## Acceptance criteria

- [ ] Child Mode fetches the current Supervision Policy only after local enrollment state has been persisted.
- [ ] Policy fetch uses the normal authenticated Child Device policy API rather than the enrollment completion response.
- [ ] Enrollment completion does not return the full Supervision Policy body.
- [ ] Child Mode stores the fetched policy separately as the last accepted policy.
- [ ] Policy-controlled monitoring and collection do not start before the policy is locally stored.
- [ ] Child Mode starts only behavior enabled by the stored policy.
- [ ] Child Mode acknowledges the applied policy version through the normal policy acknowledgement path.
- [ ] Policy Application State moves from desired-only to applied only after Child Mode acknowledgement.
- [ ] Guardian-facing state can distinguish Active Enrollment from policy pending/applied.
- [ ] Tests cover persistence-before-fetch ordering, policy storage, no policy-controlled behavior before storage, acknowledgement, and desired/applied state transitions using fake policy clients where appropriate.

## Blocked by

- .scratch/child-device-enrollment/issues/04-complete-enrollment-with-keystore-proof.md
