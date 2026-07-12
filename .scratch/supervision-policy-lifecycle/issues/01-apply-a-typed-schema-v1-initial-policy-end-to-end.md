Status: ready-for-agent

# Apply a typed schema-v1 Initial Supervision Policy end to end

## Parent

[Supervision Policy Lifecycle Foundation](../PRD.md) — user stories 36–44, 50–53, 71, and 74–76.

## What to build

Replace the raw-JSON-only Initial Supervision Policy path with an explicit schema-v1 contract that Convex and Child Mode both validate. Enrollment and authenticated health reporting must carry Child Mode's maximum supported policy schema. Child Mode must fetch and parse the complete typed Initial Supervision Policy, activate it through one high-level policy-application seam, persist it as the last accepted policy only after successful activation, and then acknowledge it through the existing authoritative policy and command lifecycle.

Use a clean pre-release schema change: all newly created and test policies declare schema version 1, without production migration compatibility. Preserve the previous last accepted policy when parsing or activation fails; any downloaded candidate retained for retry must be distinct from accepted offline state.

## Acceptance criteria

- [x] Every Supervision Policy declares a required schema version and contains explicitly validated typed sections for App Blocking, Safe Browsing, Active Screen Safety, and Screen Time Summaries; arbitrary feature JSON is not accepted.
- [x] Initial Supervision Policy creation writes schema version 1 with the existing privacy-safe defaults, and all fixtures and tests use the explicit schema.
- [x] Child Mode reports its maximum supported policy schema during enrollment and subsequent authenticated health reporting, and Convex retains the value at an Active Enrollment-associated boundary.
- [x] The Child policy endpoint returns the complete schema-versioned contract, and typed Android parsing rejects unsupported schemas, missing fields, invalid types, and inconsistent content safely.
- [x] One high-level Child policy-application seam distinguishes success, retryable failure, and safe permanent rejection without exposing raw runtime exceptions.
- [x] Successful initial application occurs in the order validate/activate, persist as last accepted, acknowledge; the fetched candidate is never treated as accepted before activation succeeds.
- [x] Parsing, activation, process-death, and persistence failures preserve the previous last accepted policy and send no false acknowledgement.
- [x] Successful acknowledgement still advances Policy Application State and completes the matching `apply_policy_version(1)` command idempotently.
- [x] Existing enrollment, authenticated Child HTTP, coordinator, startup, periodic reconciliation, and command lifecycle tests continue to pass with the typed schema.
- [x] Tests exercise behavior through the public enrollment/Child HTTP boundaries and the Android coordinator/runtime boundary rather than asserting helper calls.

## Blocked by

None - can start immediately.
