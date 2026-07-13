Status: ready-for-agent

# Accept and stream disclosed Remote Audio

## Parent

[Remote Audio](../PRD.md) — user stories 14, 16–17, 24, 30, 33–41, 49–53, and 59–72.

## What to build

Turn an awaiting Remote Audio Request into a real, disclosed, Child-to-Guardian Remote Audio Session. After the unlocked Child chooses Start audio, start a non-sticky microphone foreground service, present its ongoing connecting notice before capture, verify microphone readiness, and atomically advance the request to connecting. Child Mode creates the single send-only WebRTC offer; the initiating Guardian Device creates the receive-only answer; both publish bounded, idempotent ICE signaling through authenticated Convex realtime functions.

Use the pinned native WebRTC distribution behind Cereveil-owned peer interfaces. Stream one fixed mono Opus track from Child Mode and play it through the Guardian Device speaker. Guardian Mode never opens its microphone and exposes no talkback, route control, recording, transcript, or saving. Only Child Mode may declare the request active after its peer connection reports connected.

Complete the happy-path safety controls in the same slice: Child Stop and Guardian Stop close local media first, then terminate the backend request and apply cooldown. The fixed backend deadline must also end capture and playback so this slice is independently safe and demonstrable before later interruption hardening.

## Acceptance criteria

- [ ] Start audio cannot execute until device unlock and cannot open the microphone, start WebRTC, or publish an offer before the foreground-service notification is active.
- [ ] The Child microphone service is declared with the correct API 36 foreground-service type and permissions, is non-sticky, and exposes an immediate unauthenticated Stop action.
- [ ] Failure to start the service or acquire the microphone terminates without capture and applies normal privacy-safe cleanup and cooldown.
- [ ] Child Start atomically changes an authorized live request from `awaiting_child` to `connecting`; Guardian Mode cannot perform Child-owned lifecycle transitions.
- [ ] Child Mode creates exactly one send-only SDP offer and the initiating Guardian Device creates exactly one receive-only answer.
- [ ] Each offer and answer is limited to 64 KiB; each endpoint may publish at most 32 ICE candidates of at most 4 KiB with client idempotency keys.
- [ ] Convex enforces request ownership, sender/type rules, payload bounds, candidate counts, idempotency, and signal expiry no later than request expiry.
- [ ] Authorized request state supplies configured STUN URLs, uses the development/demo default when appropriate, requires explicit production STUN configuration, and supplies no TURN credentials.
- [ ] Both role flavors use the pinned WebRTC artifact behind replaceable Cereveil-owned peer abstractions rather than leaking library types through feature state or UI.
- [ ] Child Mode publishes one mono Opus audio track at the agreed fixed profile with gain control and noise suppression and without return audio or acoustic echo cancellation.
- [ ] Guardian Mode is receive-only, requests no microphone permission, and plays through the speaker without earpiece, Bluetooth, wired-headset, or route controls.
- [ ] Neither role exposes talkback, duplex audio, recording, transcription, export, download, or save behavior, and no audio enters Convex state or logs.
- [ ] Only Child Mode may mark `connecting` as `active`, after its peer connection reports connected; Guardian UI observes awaiting, connecting, and active truthfully.
- [ ] Child Stop disables the local audio track, releases the microphone, closes the peer connection, and removes the foreground notice before its terminal mutation.
- [ ] Guardian Stop closes playback and its peer connection before its terminal mutation.
- [ ] The backend and both endpoints enforce the original fixed two-minute request deadline, after which no local capture or playback remains.
- [ ] Stop and deadline termination remove request, signaling, command, and FCM delivery-attempt state and leave only the three-minute cooldown.
- [ ] Backend signaling tests, fake-peer coordinator tests, role-specific build/permission checks, and a successful local two-endpoint smoke path prove real audio reaches the Guardian speaker.

## Blocked by

- [02 — Request and decline Remote Audio end to end](02-request-and-decline-remote-audio-end-to-end.md)
