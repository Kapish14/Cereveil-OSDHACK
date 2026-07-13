Status: ready-for-agent

# Harden Remote Audio lifecycle and interruption handling

## Parent

[Remote Audio](../PRD.md) — user stories 15, 20, 23–24, 35–51, 62, and 78–80.

## What to build

Make the working Remote Audio Session fail closed across Android lifecycle, concurrent terminal actions, clock changes, and transient or terminal media failures. Require the initiating Guardian Device's dedicated Remote Audio screen to remain visibly foregrounded; navigation, backgrounding, locking, or destruction closes playback locally and terminates. Ensure the Child microphone service never reconstructs or resumes capture after service or process death.

Enforce the backend-owned deadline independently in Convex and both Android endpoints using server-time offset plus Android monotonic elapsed time. Treat phone calls, audio-focus loss, microphone contention, audio-route failure, peer failure, and peer close as terminal. Permit only a five-second `DISCONNECTED` recovery window while the Child notice stays visible and the original deadline continues; do not perform ICE restart.

Make every terminal route local-first and first-writer-wins. Concurrent Child Stop, Guardian Stop, second-Guardian termination, expiry, failure, screen loss, and cleanup must converge on no live media, generic peer-facing completion, complete deletion of indirect records, and one minimal cooldown. Restrict all Remote Audio observability to anonymous aggregate counters.

## Acceptance criteria

- [ ] The initiating Guardian Device keeps a request alive only while the dedicated Remote Audio screen is visibly foregrounded; navigation, backgrounding, locking, and screen destruction close local media before attempting termination.
- [ ] Guardian Mode has no playback foreground service and cannot continue speaker audio after its Remote Audio screen leaves the foreground.
- [ ] Either active Guardian Device on the Guardian Account may terminate, but a non-initiating device never receives signaling or media.
- [ ] Child service destruction or process death releases the microphone and track immediately, uses no sticky restart, and never reconstructs or resumes the prior session.
- [ ] Reconciliation after Child restart terminates any still-live request without capture; a later session requires a fresh Guardian request and fresh Child Start action.
- [ ] Both endpoints derive remaining time from backend `serverNow` and enforce shutdown using monotonic elapsed time; wall-clock, time-zone, and automatic-time changes cannot extend media.
- [ ] Convex schedules guarded terminal cleanup at the fixed expiry and stale scheduled work cannot affect a newer request.
- [ ] Phone-call start, audio-focus loss, microphone contention, and audio-route failure terminate immediately and never pause/resume.
- [ ] WebRTC `DISCONNECTED` may recover only within five seconds while the Child notice remains visible and the fixed deadline continues.
- [ ] WebRTC `FAILED`, `CLOSED`, or five seconds without recovery terminates; v1 performs no ICE restart or deadline extension.
- [ ] Every Child and Guardian stop/failure path closes local capture or playback before its Convex mutation, and mutation failure never resumes media.
- [ ] Terminal mutations are idempotent and first-writer-wins across Child, either Guardian Device, scheduled expiry, media failure, and duplicate retries.
- [ ] Peers that did not directly observe a reason show only generic request-ended or audio-ended text; specific locally known reasons are ephemeral and never persisted.
- [ ] Every terminal path deletes the request, all signals, the typed command, and FCM delivery-attempt rows before leaving only minimal cooldown state.
- [ ] Remote Audio records bypass generic seven-day command retention and cannot be reconstructed from logs, crashes, analytics, notices, or support data.
- [ ] Observability contains only anonymous aggregate counters without per-request identifiers, actors, timestamps, durations, terminal outcomes, SDP, ICE, or audio.
- [ ] Deterministic backend and Android tests cover all terminal races, lifecycle transitions, clock manipulation, process death, audio interruptions, network recovery, cleanup, and generic presentation.

## Blocked by

- [03 — Accept and stream disclosed Remote Audio](03-accept-and-stream-disclosed-remote-audio.md)
