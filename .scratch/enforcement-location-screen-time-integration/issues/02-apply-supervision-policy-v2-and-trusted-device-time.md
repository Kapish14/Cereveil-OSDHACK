Status: ready-for-agent

# Apply Supervision Policy v2 and Trusted Device Time

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 12–30, 32, 67–68, 98–99, 102, and 130–132.

## What to build

Replace the obsolete development policy shape with schema v2 and carry one immutable complete snapshot through the existing desired/applied lifecycle. Schema v2 contains bounded App Blocking rules, Location Sharing, and Screen Time. Guardian Mode can configure independent Manual and recurring Scheduled Blocks from the App Catalog, while Child Mode validates and activates the entire snapshot atomically and reports schema support before new settings are offered.

Add Trusted Device Time to Protection Setup and Supervision Health. Both automatic date/time and automatic time zone must be available for trustworthy schedules and local-day boundaries; losing either produces Protection Degraded and a deduplicated Tamper Alert through the existing heartbeat path.

## Acceptance criteria

- [ ] Schema v2 truthfully models `appBlocking`, `locationSharing`, and `screenTime`; it removes the `screenTimeSummariesEnabled` concept from new contracts and defaults Location Sharing and Screen Time off.
- [ ] App Blocking contains a master enabled flag, at most 100 unique package rules, an independent Manual Block flag, and at most eight stable-identity schedules per package.
- [ ] Schedule validation accepts non-empty weekday sets and local start/end minutes, rejects equal times and malformed or duplicate values, and represents overnight windows without fake infinite schedules.
- [ ] Guardian mutations use the existing actor wrapper, optimistic concurrency and Save-operation identity, build from the latest desired complete snapshot, and preserve unrelated sections.
- [ ] Creating the first rule enables App Blocking in the same version; disabling the master preserves rules; removing the last rule does not implicitly change the master.
- [ ] Guardian app selection uses the authorized latest App Catalog, omits Exempt Apps, and Convex rejects known exemptions and invalid bounds.
- [ ] App-rule pending state compares the complete desired and applied section, reuses the inline acknowledgement spinner, changes to “Waiting for Child Device” after the established delay, and shows permanent rejection as “Couldn't apply.”
- [ ] Child Mode reports schema-v2 support, validates a complete candidate, persists and activates it atomically, acknowledges only after activation, and retains the prior accepted policy on rejection or while Offline.
- [ ] Schedule rules remain stored by package when that package is temporarily absent from the latest App Catalog.
- [ ] Protection Setup and heartbeat capability state require automatic date/time and automatic time zone; disabling either yields Protection Degraded plus one deduplicated Tamper Alert without a separate capability event channel.
- [ ] Development policies and fixtures are reset or migrated to schema v2 without adding an unnecessary production backfill.
- [ ] Backend, Child policy coordinator, Guardian ViewModel, and Compose tests cover bounds, stale/concurrent saves, atomic application, unsupported schema, pending/applied truth, time capability restoration, and tamper deduplication.

## Blocked by

- [01 — Synchronize the latest App Catalog end to end](01-synchronize-the-latest-app-catalog-end-to-end.md)
