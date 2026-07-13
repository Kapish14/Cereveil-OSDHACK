Status: ready-for-agent

# Complete cross-feature lifecycle, retention, authorization, and privacy hardening

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 139–151.

## What to build

Audit the completed feature paths as one ownership and lifecycle system, then close cross-feature gaps that cannot be proven within a single tracer bullet. Basic authorization, expiry, and privacy remain acceptance criteria of every feature issue; this issue verifies their composition across End Supervision, Replace Child Device, revocation, scheduled transitions, bounded cleanup, command retention, logging, and operational measurement.

Feature-owned data must remain in App Catalog, access, location, and Screen Time modules rather than commands or notifications. Retention removes transient state without creating history: terminal Access Requests and expired Grants after 24 hours; terminal location/screen refresh and abandoned staging after 15 minutes; existing command and FCM operational records after seven days.

## Acceptance criteria

- [ ] Every Guardian public feature operation resolves an active Guardian actor and cannot read or mutate another Household through supplied identifiers.
- [ ] Every Child route resolves and transactionally revalidates the full credential, device, enrollment, Child Profile, and Household actor chain, including revoked and replaced-device cases.
- [ ] Backend identity and ownership come from authenticated actors rather than client-supplied Household, profile, enrollment, or device identifiers.
- [ ] Feature records remain authoritative in their domain modules; commands and notifications contain only bounded opaque references and delivery state.
- [ ] Location and Screen Time timestamps are checked against backend request windows, plausible bounds, latest-state monotonicity, and local-day validity where applicable.
- [ ] All lifecycle and expiry queries use explicit indexed fields, bounded batches, scheduled transitions at creation, and cleanup backstops without full-table scans or unbounded deletes.
- [ ] Terminal Access Requests and expired Grants are physically deleted after 24 hours; terminal location/screen refresh records and abandoned staging after 15 minutes; existing seven-day command/FCM retention is unchanged.
- [ ] End Supervision deletes/cancels latest catalog, location, Screen Time, access and transient work, while Replace Child Device does not present former enrollment-produced state as current for the replacement.
- [ ] Package-based policy remains with the retained Child Profile across Replace Child Device, but the new enrollment must produce its own latest device state.
- [ ] Desired-policy disablement and actor revocation prevent new collection or authority even when commands, FCM messages, uploads, or Guardian Devices race with the transition.
- [ ] Logs, request diagnostics, crash reporting, FCM and metrics exclude exact coordinates, map viewport, app labels/lists, per-app totals, policy bodies, grant details, Child identity, tokens, request bodies, and raw exceptions.
- [ ] Allowlisted outcome metrics cover creation, delivery, completion, rejection/failure, expiry, deduplication and cleanup without sensitive content.
- [ ] Cross-feature backend tests cover cross-Household access, revoked actor chains, concurrent lifecycle changes, scheduled cleanup, bounded batches, retention boundaries, and privacy-safe observability.

## Blocked by

- [06 — Approve, persist, and expire Access Grants](06-approve-persist-and-expire-access-grants.md)
- [08 — Refresh the Child's location once through FCM](08-refresh-the-childs-location-once-through-fcm.md)
- [10 — Refresh latest-only Screen Time on demand](10-refresh-latest-only-screen-time-on-demand.md)
