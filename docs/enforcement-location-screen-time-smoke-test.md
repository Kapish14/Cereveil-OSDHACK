# Enforcement, latest location, and screen-time smoke test

Record only pass/fail and device/OS/build versions. Do not record a Child identity, coordinates, installed-app list, per-app totals, tokens, policy bodies, or request payloads.

## Recorded run — 2026-07-13

- PASS: both debug variants assembled, installed side-by-side on a connected physical Android device, and launched without an application crash.
- PASS: missing-key build exercised the compile/install/launch side of the provider-neutral fallback contract.
- NOT RUN: authenticated two-role scenarios below. The connected device was locked and no test Guardian credentials or second interactive device were supplied. These checks remain manual release gates and are intentionally not marked as passed.

## Prerequisites

- Two Android test roles (two devices are preferred; the separately packaged Guardian and Child debug apps can coexist on one device for limited testing).
- Child has Accessibility, Usage Access, precise/background Location, Notifications, trusted automatic date/time and battery exemption.
- Guardian is enrolled and the applied schema-v2 policy has the feature under test enabled.
- FCM is configured. Maps testing additionally uses the restricted key described in [development-google-maps.md](development-google-maps.md).

## App blocking and access

- [ ] Latest launchable apps appear after enrollment; exempt/system/repair apps never appear.
- [ ] Package install/remove triggers a complete catalog replacement, never a partial list.
- [ ] Manual, weekday, overnight, and overlapping schedules enforce in the current time zone.
- [ ] The overlay covers normal, split-screen, and picture-in-picture windows; Home escapes safely.
- [ ] Enforcement survives Child process death, reboot, Offline use, and a missed FCM wake-up.
- [ ] Accessibility loss produces Protection Degraded/tamper; restoration converges.
- [ ] Ask Guardian deduplicates while pending. Deny, expiry, 15/30/45/60-minute approval, absolute local expiry, and first-response-wins behave truthfully.

## FCM and reconciliation

- [ ] Policy/screen-time commands use normal priority; location/access-grant wake-ups use high priority.
- [ ] Payloads contain category/type/opaque identifiers only.
- [ ] Duplicate, delayed, reordered, or missed pushes converge through `/child/commands` without duplicate authority.
- [ ] A location refresh shows the visible Child notification before measurement.

## Latest location

- [ ] Applied enablement starts roughly 15-minute work and 250 m movement updates capped at five minutes; disablement stops collection.
- [ ] Offline measurements are discarded; reconnect captures a new measurement.
- [ ] One-time refresh replaces latest state only when newer; failure preserves the previous point.
- [ ] Guardian independently shows coordinates, measurement age, accuracy, connectivity/capability, and the 30-minute stale warning.
- [ ] A configured map shows exactly one marker and accuracy circle. A missing/invalid key preserves the card and `geo:` action.
- [ ] No route, session, or location-history surface/table exists.

## Current-day screen time

- [ ] Opening the selected child automatically requests current data; a pending request is reused for two minutes.
- [ ] Values match Android's whole-current-local-day per-launchable-app totals; no Cereveil aggregation/session reconstruction occurs.
- [ ] Empty usage is valid. Incomplete uploads are invisible and completion atomically replaces the prior snapshot.
- [ ] Midnight invalidates the snapshot; disabling the policy immediately deletes backend state and stops requests.
- [ ] No daily/weekly history or raw usage events are uploaded.

## Lifecycle and privacy

- [ ] Revoked/replaced Child credentials cannot upload or reconcile; former-enrollment latest state is not shown for the replacement.
- [ ] End Supervision removes catalog, access, location, screen-time, command, notification, and local operational state.
- [ ] Retention backstops physically remove access records after 24 hours and refresh/staging records after 15 minutes in bounded batches.
- [ ] Logs, crashes, metrics, and FCM diagnostics contain none of the sensitive values listed at the top of this document.
