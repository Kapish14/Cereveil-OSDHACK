Status: ready-for-agent

# Configure Safe Browsing and Safe Search as one validated policy section

## Parent

[Supervision Policy Lifecycle Foundation](../PRD.md) — user stories 5–6, 18–21, 28–31, 51, and 55–57.

## What to build

Extend the shared Guardian policy lifecycle with a typed Safe Browsing section containing Safe Browsing and dependent Safe Search Enforcement settings. The development-only Guardian surface must make invalid combinations difficult to express, while Convex independently validates the complete resulting policy. A successful change must produce and reconcile a complete immutable snapshot through the same desired/applied and command path as the first tracer bullet.

Safe Browsing VPN, DNS filtering, Domain Rules, blocklists, and real Safe Search Enforcement are not part of this issue.

## Acceptance criteria

- [x] Guardian Mode reads Safe Browsing and Safe Search desired/applied state from the focused authoritative policy query.
- [x] A feature-specific Safe Browsing mutation changes only that typed section while Convex preserves every unrelated section from the latest desired snapshot.
- [x] Safe Search Enforcement cannot be enabled when Safe Browsing is disabled; the Guardian UI prevents the combination and Convex rejects it if submitted directly.
- [x] Turning off Safe Browsing deliberately turns off Safe Search Enforcement in the same requested section rather than relying on silent backend correction.
- [x] Effective changes, no-ops, retry replay, operation misuse, stale expected versions, schema compatibility, immutable versioning, desired-state updates, command creation, and acknowledgement use the shared lifecycle without feature-specific duplication.
- [x] While Safe Browsing is pending, its own controls are disabled and show inline progress/waiting; unrelated feature controls remain available.
- [x] A later unrelated update is built from the latest desired snapshot and preserves the pending Safe Browsing intent.
- [x] Child Mode parses and atomically accepts the complete updated policy before acknowledgement, preserving the previous accepted snapshot on failure.
- [x] Backend tests cover valid dependent combinations and direct invalid submissions; Guardian tests cover dependent-control behavior and authoritative pending/applied rendering.
- [x] The issue is demonstrable through the development policy screen and completes without implementing the Safe Browsing enforcement engine.

## Blocked by

- [02 — Change Screen Time Summaries through the complete policy lifecycle](02-change-screen-time-summaries-through-the-complete-policy-lifecycle.md)
