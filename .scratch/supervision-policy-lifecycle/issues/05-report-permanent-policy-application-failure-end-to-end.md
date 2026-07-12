Status: ready-for-agent

# Report permanent Supervision Policy application failure end to end

## Parent

[Supervision Policy Lifecycle Foundation](../PRD.md) — user stories 15–17, 37, 44–47, and 69–70.

## What to build

Complete the permanent-rejection path for the current desired Supervision Policy. Child Mode must distinguish retryable application conditions from safe non-retryable rejection, preserve the previous last accepted snapshot in both cases, and reject only the matching current policy command with an allowlisted reason. Convex must map a valid permanent rejection into failed Policy Application State without allowing stale or superseded work to alter newer desired or applied state. Guardian Mode must replace indefinite progress with an inline Could not apply state and recover normally when a later valid version becomes desired.

## Acceptance criteria

- [x] Policy Application State explicitly represents pending, applied, and failed outcomes and stores only allowlisted safe failure reasons for the current desired version.
- [x] Child policy application distinguishes success, retryable failure, and permanent rejection; raw Android exceptions, policy content, and sensitive device details are never submitted as reasons.
- [x] Connectivity, delivery, token refresh, and ordinary retryable activation failures remain pending and continue reconciliation rather than immediately becoming failed.
- [x] Permanent rejection preserves the prior accepted policy, sends no policy acknowledgement, and transitions only the matching current command/application state.
- [x] Stale, expired, cancelled, superseded, duplicate, or wrong-device rejection cannot mark a newer desired version failed or overwrite an applied version.
- [x] Guardian policy state exposes failed status and its safe presentation reason without exposing internal implementation details.
- [x] The development feature control stops spinner/waiting presentation and shows inline Could not apply for a permanently failed desired value.
- [x] A later effective Guardian update clears the prior failure, becomes pending, creates current reconciliation work, and can advance to applied normally.
- [x] Backend integration tests cover permanent rejection, retryable failure, stale rejection, duplicate rejection, later recovery, and command/application-state consistency.
- [x] Android Child and Guardian tests cover last-accepted preservation, no false acknowledgement, inline failure presentation, recreation from authoritative failed state, and recovery.

## Blocked by

- [02 — Change Screen Time Summaries through the complete policy lifecycle](02-change-screen-time-summaries-through-the-complete-policy-lifecycle.md)
