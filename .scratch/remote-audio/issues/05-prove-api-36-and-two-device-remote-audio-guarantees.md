Status: ready-for-human

# Prove API 36 and two-device Remote Audio guarantees

## Parent

[Remote Audio](../PRD.md) — verification of user stories 1–80.

## What to build

Close the Remote Audio effort with full regression, API 36 instrumentation, and a real two-device demonstration. Exercise the feature through its public Guardian and authenticated Child boundaries, high-level Android coordinators, notification and foreground-service surfaces, role-specific manifests, and real WebRTC peers. Fix any defects discovered while proving the agreed behavior; do not weaken acceptance to accommodate environment or implementation shortcuts.

The final demonstration must show one Guardian Device requesting, one Child Device explicitly starting, actual live microphone audio playing through the Guardian speaker, immediate Child Stop, Guardian visibility teardown, fixed expiry, cooldown, and complete deletion of Remote Audio state. STUN-only failure on a restrictive network remains an expected product outcome, but the successful demonstration must use a topology where direct connectivity is possible.

## Acceptance criteria

- [ ] Convex tests cover eligibility, authorization, ES256 identity, idempotent creation, two-Guardian contention, Child-owned transitions, signaling limits, terminal races, fixed expiry, cooldown, and complete no-history deletion through public/authenticated boundaries.
- [ ] Existing Child HTTP feature integration suites continue passing after the ES256 cutover.
- [ ] Guardian and Child coordinator tests cover request, decline, unlock-required Start, notification-before-capture ordering, connecting, active, local-first Stop, generic completion, screen visibility, process death, monotonic deadline, interruption, and five-second recovery.
- [ ] Compose tests cover Guardian unavailable, ready, awaiting, connecting, active, generic ended, and cooldown states plus Child in-app request/session controls.
- [ ] API 36 instrumentation proves high-importance one-alert request notification behavior, ongoing presentation, lock-screen-safe Decline, unlock-required Start, no full-screen intent, microphone foreground-service startup, active Stop, and teardown.
- [ ] Instrumentation proves inability to present the request notification or start the microphone foreground service results in zero capture.
- [ ] Guardian and Child variants build and pass their unit tests; Guardian requests no microphone permission, and Child declares only the required microphone foreground-service capability.
- [ ] A two-device run proves actual Child microphone audio reaches the initiating Guardian Device speaker through STUN-only WebRTC.
- [ ] The non-initiating Guardian Device cannot receive media or signaling and can only issue the privacy-safe terminal action.
- [ ] Child Stop releases capture immediately even when terminal network delivery is disrupted.
- [ ] Leaving or backgrounding the Guardian Remote Audio screen ends local playback and prevents background continuation.
- [ ] The complete awaiting, connecting, and active lifecycle never exceeds two minutes from backend request creation on either endpoint.
- [ ] Every terminal outcome blocks another request for three minutes and makes it available when cooldown expires.
- [ ] Post-session inspection finds no Remote Audio Request, Signal, typed command, FCM delivery-attempt, terminal outcome, or session-history rows; only an unexpired minimal cooldown may remain.
- [ ] Logs, crash output, and test diagnostics contain no audio, SDP, ICE payload, request/session identifier, Child or Guardian identifier, per-request timestamp, duration, or terminal reason.
- [ ] The final verification record documents tested API level, role builds, network topology, successful behaviors, expected STUN-only limitations, and any environment-only manual steps without adding retained user session data.

## Blocked by

- [01 — Authenticate Child Devices to Convex realtime with ES256](01-authenticate-child-devices-to-convex-realtime-with-es256.md)
- [02 — Request and decline Remote Audio end to end](02-request-and-decline-remote-audio-end-to-end.md)
- [03 — Accept and stream disclosed Remote Audio](03-accept-and-stream-disclosed-remote-audio.md)
- [04 — Harden Remote Audio lifecycle and interruption handling](04-harden-remote-audio-lifecycle-and-interruption-handling.md)

## Comments

- 2026-07-14: Automated backend, role-unit, Android 36 compile/manifest, and APK assembly verification is recorded in [verification.md](../verification.md). No device or AVD is attached, so API 36 instrumentation and the required real two-device audio demonstration remain `ready-for-human` rather than being claimed as passed.
