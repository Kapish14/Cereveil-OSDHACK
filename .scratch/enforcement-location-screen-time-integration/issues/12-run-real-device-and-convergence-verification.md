Status: ready-for-agent

# Run real-device and convergence verification

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 152–153.

## What to build

Exercise the integrated product on real Android devices and complete the automated convergence matrix around the platform boundaries that fakes cannot prove. Verify Accessibility overlays, Usage Access totals, FCM priority/wake-up behavior, one-time high-accuracy location, Google Maps rendering with a supplied restricted development key, local persistence, and Guardian truthfulness.

Turn every discovered regression into focused automated coverage at the closest stable public seam. The result should demonstrate that Convex remains authoritative and the Child converges correctly despite missed, duplicate, delayed, stale, concurrent, Offline, revoked, replaced-device, and policy-disabled work.

## Acceptance criteria

- [ ] A documented real-device smoke run verifies foreground detection and Block Screen behavior for normal, split-screen, picture-in-picture, Home escape, Exempt Apps, and Accessibility loss/restoration.
- [ ] A real-device run verifies Manual and Scheduled Blocks, local timezone/time changes, trusted-time degradation, reboot/process recovery, Offline enforcement, grant application, and local grant expiry.
- [ ] FCM smoke coverage verifies normal versus high priority, generic payload content, duplicate delivery, missed-push command reconciliation, Access Request notices, approval wake-up, and the visible location-refresh notification.
- [ ] Location smoke coverage verifies low-power latest state, one-time refresh, poor-accuracy display, stale warning, failed refresh preservation, Offline/reconnect behavior, and absence of a route/session UI.
- [ ] A supplied restricted development key verifies one Google Map marker and accuracy circle; missing/invalid key verifies the coordinate/status card and `geo:` fallback.
- [ ] Usage Access smoke coverage verifies Android's whole-current-day per-app totals, launchable/exempt filtering, empty usage, automatic selected-child refresh, atomic replacement, midnight invalidation, and disablement deletion.
- [ ] Automated tests cover missed, duplicate, delayed, reordered, stale, future-invalid, concurrent, Offline, revoked, replaced-device, and disabled-policy messages across all command types.
- [ ] Automated tests prove first-response-wins access resolution, request deduplication/expiry, grant absolute expiry, newer-only location, complete-only Screen Time publication, and desired/applied policy gates.
- [ ] Automated and manual evidence confirms no location history, Screen Time history, raw usage events, package metadata expansion, or sensitive FCM/log content was introduced.
- [ ] The smoke procedure records environment prerequisites and observable pass/fail outcomes without recording Child identities, coordinates, installed-app lists, or per-app usage values.

## Blocked by

- [04 — Enforce Manual and Scheduled App Blocks on Android](04-enforce-manual-and-scheduled-app-blocks-on-android.md)
- [06 — Approve, persist, and expire Access Grants](06-approve-persist-and-expire-access-grants.md)
- [08 — Refresh the Child's location once through FCM](08-refresh-the-childs-location-once-through-fcm.md)
- [09 — Render latest location with Google Maps SDK](09-render-latest-location-with-google-maps-sdk.md)
- [10 — Refresh latest-only Screen Time on demand](10-refresh-latest-only-screen-time-on-demand.md)
- [11 — Complete cross-feature lifecycle, retention, authorization, and privacy hardening](11-complete-cross-feature-lifecycle-retention-authorization-and-privacy-hardening.md)
