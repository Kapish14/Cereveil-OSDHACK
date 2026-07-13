Status: ready-for-agent

# Synchronize the latest App Catalog end to end

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 1–11.

## What to build

Give Guardian Mode an authoritative, latest-only catalog of the user-launchable apps currently available on an enrolled Child Device. Child Mode discovers only launchable packages, removes safety-critical Exempt Apps, uploads package name and device-resolved label through bounded generations, and atomically publishes a complete replacement. Guardian Mode shows the current catalog and its freshness, while existing policy references to a temporarily missing package remain representable and resume when that package is reinstalled.

The catalog is app-selection state, not installation history. Do not upload icons, permissions, APK or signing metadata, installer data, timestamps, or system components, and do not require broad package visibility.

## Acceptance criteria

- [ ] Authenticated Child operations can start, batch, and complete a bounded catalog generation, with identity derived from the full active Child Device actor chain.
- [ ] A completed generation atomically becomes the sole visible latest catalog; incomplete, duplicate, stale, or abandoned generations never become partially visible.
- [ ] Child discovery includes user-launchable packages only, uses package visibility compatible mechanisms without `QUERY_ALL_PACKAGES`, and applies a non-overridable Exempt App classifier.
- [ ] Cereveil, Home/launcher, System UI, current dialer/emergency surfaces, and required Settings/repair surfaces cannot appear as Guardian-selectable apps.
- [ ] Payloads contain only package name and the Child Device-resolved display label, with validation and batching limits that prevent oversized writes.
- [ ] Child Mode synchronizes promptly after enrollment and reconciles package add/remove, reinstall, restart, and a safe fallback cycle without retaining catalog history.
- [ ] Guardian Mode shows the latest catalog, device-resolved labels, synchronization freshness, loading, empty, Offline, and failure states through an authorized query.
- [ ] A package removed from the current catalog can remain visible when referenced by policy as not currently installed, and the same package identity becomes installed again after reconciliation.
- [ ] Protection Setup discloses sharing of latest installed app names and package identities.
- [ ] Replace Child Device and End Supervision behavior does not expose a former enrollment's catalog as current for a new enrollment and removes owned catalog state when supervision ends.
- [ ] Backend, Android coordinator, repository/ViewModel, and user-visible UI tests cover authorization, generation atomicity, lifecycle reconciliation, exemptions, bounded payloads, and privacy-safe errors/logging.

## Blocked by

None - can start immediately.
