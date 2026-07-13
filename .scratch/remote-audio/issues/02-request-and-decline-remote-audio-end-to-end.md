Status: ready-for-agent

# Request and decline Remote Audio end to end

## Parent

[Remote Audio](../PRD.md) — user stories 1–13, 18–22, 25–32, 38–39, 42, 49, 54–64, and 77–80.

## What to build

Deliver the first complete Remote Audio Request path from Guardian Mode to a Child-visible choice and back to privacy-safe termination. Guardian Mode opens a dedicated foreground screen, observes authoritative eligibility, and creates one idempotent two-minute request for an Online Child Device with the required microphone, notification, enrollment, credential, and FCM state. Convex creates the transient request before its typed Child Device Command and generic high-priority FCM wake-up.

Child Mode reconciles the command and presents a high-importance, ongoing request notification. It alerts once, uses explicit lock-screen text, allows immediate Decline, requires unlock before Start audio, never uses a full-screen intent, and is mirrored inside Child Mode while open. This slice must make Decline, Guardian Cancel, expiry, notification failure, and an unavailable Start implementation fail closed and fully clean up; it must not capture microphone audio or claim that a Remote Audio Session has started. Structure the Child action coordinator so the next slice can supply the real microphone/WebRTC starter without changing command or notification semantics.

Every terminal path deletes the Remote Audio Request, typed command, and FCM delivery-attempt records and leaves only a minimal three-minute Remote Audio Cooldown. The initiating Guardian Device owns the request; another Guardian Device cannot join or replace it, although either authorized Guardian Device may terminate it.

## Acceptance criteria

- [ ] Convex stores only live `awaiting_child` Remote Audio Request state plus minimal Remote Audio Cooldown state; no terminal request row is retained.
- [ ] Request creation transactionally verifies the full Guardian and Child ownership/lifecycle chain, Online Supervision Health, last-reported microphone and notification capability, active Child FCM delivery ownership, no live request, and no cooldown.
- [ ] Guardian subscribed eligibility and the authoritative mutation expose clear unavailable, ready, busy, and cooldown outcomes without relying on client-only checks.
- [ ] A stable Guardian operation ID makes uncertain retries idempotent, and a repeated tap from the initiating Guardian Device returns its existing live request.
- [ ] A second Guardian Device cannot create, read private request details for, or take over the initiating device's request; either active Guardian Device may safely terminate it.
- [ ] Guardian Mode creates a request only from a visible dedicated screen and renders ready, awaiting Child, unavailable with reason, generic ended, and cooldown states with the fixed deadline countdown and Cancel control.
- [ ] Creation writes authoritative request state before the typed `request_remote_audio` command and schedules only a generic high-priority `child_command` FCM wake-up.
- [ ] Child command reconciliation presents exactly one high-importance ongoing request notification under duplicate command, duplicate FCM, process recreation, and retry scenarios.
- [ ] The notification uses explicit lock-screen text, alerts with sound/vibration once, never launches full screen, allows Decline without authentication, and requires unlock before Start audio.
- [ ] Command acknowledgement means the Child choice notification was successfully presented, not that audio began; an absent or expired request is handled without UI.
- [ ] Notification presentation failure and the deliberately unavailable pre-WebRTC Start path terminate without opening the microphone or publishing signaling.
- [ ] Child Decline removes local request UI before its terminal mutation; Guardian Cancel closes local request UI before its terminal mutation.
- [ ] Request expiry is fixed at two minutes after backend creation, applies cooldown, and cannot be extended by retries, delayed Child action, or device wall-clock changes.
- [ ] Decline, Cancel, expiry, and presentation failure delete the request, its typed command, and associated FCM delivery-attempt records and create only `childProfileId` plus `cooldownUntil` state.
- [ ] Cooldown blocks creation for three minutes, returns the next allowed time, and is deleted after expiry.
- [ ] Remote Audio remains outside Supervision Policy and no app exposes request history, terminal reason history, or initiator history.
- [ ] Backend, Guardian coordinator/UI, Child command/notification, authorization, timing, concurrency, and no-history tests cover the entire request-to-decline tracer path.

## Blocked by

- [01 — Authenticate Child Devices to Convex realtime with ES256](01-authenticate-child-devices-to-convex-realtime-with-es256.md)
