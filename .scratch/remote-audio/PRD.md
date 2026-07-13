Status: ready-for-agent

# Remote Audio

## Problem Statement

Cereveil has completed the enrollment, supervision, command, notification, Location, Screen Time, enforcement, and Safety Detection foundations needed for remote Child Device work, but a Guardian still cannot perform the agreed live audio check. There is no Convex Remote Audio Request state, no WebRTC signaling path, no Guardian listening surface, and no Child-facing request, foreground-service notice, or Stop control.

The original automatic-start concept is also incompatible with modern Android behavior. Cereveil targets Android API 36, where a background app cannot reliably start a microphone foreground service using the while-in-use `RECORD_AUDIO` permission merely because it received high-priority FCM. The implementation must therefore preserve the emergency check-in use case while requiring an explicit Child interaction, maintaining continuous disclosure, enforcing strict time and cooldown limits, and ensuring that neither audio nor indirect session history survives termination.

The existing Child Device JWT is another blocker. It is an HTTP-only HS256 token manually verified by Device Identity endpoints, while low-latency WebRTC signaling requires authenticated realtime Convex subscriptions and mutations on both endpoints. Remote Audio must complete the planned custom Child Device authentication boundary without breaking the authorization chain used by existing Child requests.

## Solution

Build a complete Child-confirmed Remote Audio slice. Guardian Mode opens a dedicated foreground-only Remote Audio screen for an eligible Child Profile and creates a backend-authoritative Remote Audio Request. Convex writes one live request, creates a typed Child Device Command, and sends generic high-priority FCM only as a wake-up. Child Mode reconciles authoritative state and presents a high-importance ongoing notification with Start audio and Decline actions. Start audio requires device unlock; Decline does not.

After the Child chooses Start audio, Child Mode starts a disclosed microphone foreground service, changes the request to connecting, creates a send-only WebRTC offer, and publishes bounded signaling through authenticated realtime Convex functions. The initiating Guardian Device answers, receives the single live audio track, and plays it through its speaker. Child Mode alone may declare the request active after its peer connection reports connected. The Child can stop immediately at any time, and either Guardian Device on the Guardian Account can terminate the request without gaining access to its media.

The entire awaiting-Child, connecting, and active lifecycle shares one backend-owned deadline two minutes after request creation. Convex and both endpoints independently enforce that deadline. Every terminal path is local-first, network-second, applies a three-minute Remote Audio Cooldown, and deletes the request, signaling, command, and FCM delivery-attempt records. Only a minimal Child Profile cooldown timestamp remains until it expires. No audio, recording, transcript, session record, terminal outcome, or indirect Remote Audio history is retained.

Complete Child Device custom authentication by replacing the undeployed HS256 token with a fifteen-minute ES256 custom JWT, backed by a dedicated backend signing key and public JWKS. Existing Child HTTP operations retain their current API shape but use the ES256 verifier; Remote Audio additionally uses an authenticated Convex Android client for realtime state and signaling. There are no deployed users or legacy tokens, so this is a clean cutover with no dual-token migration.

## User Stories

1. As a Guardian, I want to request live audio for an eligible Child Profile, so that I can perform a bounded check-in when needed.
2. As a Guardian, I want Remote Audio to be available only for an actively supervised Child Device, so that stale or revoked devices cannot receive requests.
3. As a Guardian, I want the Remote Audio action disabled when the Child Device is Offline, so that the UI does not promise an unavailable live feature.
4. As a Guardian, I want the action disabled when microphone or notification capability was last reported unavailable, so that the request fails predictably before disturbing the Child.
5. As a Guardian, I want the action disabled when the Child Device has no active FCM token, so that a two-minute request is not created without a viable immediate wake-up path.
6. As a Guardian, I want Convex to recheck every eligibility rule during creation, so that stale UI state cannot bypass authorization or availability rules.
7. As a Guardian, I want a dedicated Remote Audio screen, so that listening cannot continue unnoticed behind unrelated Guardian UI.
8. As a Guardian, I want a request created only after the dedicated screen is visible, so that navigation does not create an unattended request.
9. As a Guardian, I want the screen to explain why Remote Audio is unavailable, so that Offline, missing capability, missing notification delivery, busy, and cooldown states are understandable.
10. As a Guardian, I want to see a ready state with an explicit Request audio action, so that listening never begins from an accidental card tap.
11. As a Guardian, I want to see when the request is awaiting the Child, so that I know microphone capture has not started.
12. As a Guardian, I want a countdown throughout awaiting, connecting, and active states, so that the fixed deadline is visible.
13. As a Guardian, I want to cancel while awaiting the Child, so that an unnecessary request can end immediately.
14. As a Guardian, I want to stop while connecting or active, so that I remain able to end my request at any time.
15. As a Guardian, I want Remote Audio to terminate when I navigate away, background Guardian Mode, lock the device, or destroy the Remote Audio screen, so that speaker playback never continues unnoticed.
16. As a Guardian, I want playback to use the device speaker in this version, so that the implementation has one predictable output route.
17. As a Guardian, I want no microphone permission or capture in Guardian Mode, so that Remote Audio cannot become talkback or duplex communication.
18. As a Guardian, I want a repeated tap or uncertain network retry on my device to reuse the same request, so that I cannot create duplicate listening attempts.
19. As a Guardian using a second Guardian Device, I want to see that another device owns the live request, so that I cannot silently join or take over its audio.
20. As a Guardian using either authorized Guardian Device, I want to be able to terminate a live request, so that equal supervision authority can always make the privacy-safe stop decision.
21. As a Guardian, I want a three-minute cooldown after every terminal outcome, so that repeated requests cannot pressure the Child or create continuous listening.
22. As a Guardian, I want the cooldown screen to show when another request becomes available, so that I do not repeatedly retry.
23. As a Guardian, I want peer-visible completion text to remain generic, so that Child decline, Child stop, and device failures do not become retained or delivered session outcomes.
24. As a Guardian, I want my own locally observed stop or WebRTC failure explained locally, so that immediate UI remains useful without backend history.
25. As a Child, I want every incoming Remote Audio Request to be visible before microphone capture, so that listening is never covert.
26. As a Child, I want a high-importance heads-up notification with explicit lock-screen text, so that I can notice the request promptly.
27. As a Child, I want the request notification to alert with sound or vibration only once, so that the two-minute request does not repeatedly disturb me.
28. As a Child, I want the request notification to remain ongoing until action, cancellation, or expiry, so that the pending choice cannot disappear silently.
29. As a Child, I want Remote Audio never to use a full-screen intent, so that the request does not seize my current activity.
30. As a Child, I want Start audio and Decline actions, so that microphone capture requires my explicit choice.
31. As a Child, I want Decline to work immediately from the lock screen, so that I can refuse without unlocking the device.
32. As a Child, I want Start audio to require device unlock, so that another person cannot initiate capture from my locked phone.
33. As a Child, I want no microphone capture or WebRTC offer before Start audio, so that the pending notification is not merely cosmetic.
34. As a Child, I want the active microphone foreground-service notification to distinguish connecting from active audio, so that the current state is truthful.
35. As a Child, I want an immediately available Stop action throughout connecting and active states, so that I can end capture at any time.
36. As a Child, I want Stop to work without device authentication, so that stopping is never delayed by the lock screen.
37. As a Child, I want Remote Audio status and controls mirrored inside Child Mode while it is open, so that the app and notification agree.
38. As a Child, I want no accessibility overlay placed over other apps for Remote Audio, so that disclosure remains persistent without obstructing device use.
39. As a Child, I want the request to fail without capture when its notification cannot be presented, so that microphone use never occurs without disclosure.
40. As a Child, I want the request to fail without capture when Android cannot launch the microphone foreground service, so that partial setup cannot become undisclosed recording.
41. As a Child, I want Stop to release the microphone and remove the active notification before contacting Convex, so that stopping does not depend on network availability.
42. As a Child, I want Decline to remove the local request notification before contacting Convex, so that a failed network call cannot keep pressuring me.
43. As a Child, I want process or service death to release capture immediately, so that a crashed app cannot leave microphone ownership ambiguous.
44. As a Child, I want the microphone service never to auto-resume after process death, so that capture always requires a fresh request and fresh Start action.
45. As a Child, I want phone calls, audio-focus loss, microphone contention, and audio-route failure to end the request, so that interrupted capture cannot resume unexpectedly.
46. As a Child, I want a brief network handoff to recover only while the persistent notice remains visible, so that ordinary connectivity changes do not unnecessarily end an acknowledged session.
47. As a Child, I want a disconnected WebRTC connection to end after five seconds without recovery, so that failed audio does not linger.
48. As a Child, I want no ICE restart in this version, so that a failed connection cannot silently renegotiate into later capture.
49. As a Child, I want the complete request lifecycle capped at two minutes from Guardian creation, so that delayed action or connection never extends the promised boundary.
50. As a Child, I want local capture to stop at the deadline even while Offline, so that Convex connectivity is not required for the safety limit.
51. As a Child, I want Android monotonic time used for the local countdown, so that wall-clock or time-zone changes cannot extend capture.
52. As a Child, I want only live send-only audio transmitted, so that my device never receives Guardian speech or opens a duplex channel.
53. As a Child, I want audio never recorded, transcribed, exported, or saved at either endpoint, so that ambient audio cannot become retained surveillance data.
54. As a Child, I want no request or session history after termination, so that neither app can later reconstruct when listening occurred.
55. As a Child, I want FCM to wake authoritative reconciliation rather than carry Remote Audio details, so that third-party push payloads remain minimal.
56. As a Child, I want a missed or duplicate FCM wake-up handled idempotently, so that notification delivery behavior cannot duplicate requests.
57. As a Child, I want an already expired or absent request command acknowledged without UI, so that stale commands cannot produce a late request.
58. As a Child, I want a presented request notification to acknowledge the command without claiming audio started, so that delivery and capture remain distinct facts.
59. As a developer, I want Convex to own Remote Audio Request authorization and lifecycle state while WebRTC carries media, so that audio never passes through or persists in the backend.
60. As a developer, I want request states limited to awaiting Child, connecting, and active, so that terminal session history is not stored.
61. As a developer, I want Child Mode alone to advance connecting and active states, so that Guardian Mode cannot falsely assert that capture began.
62. As a developer, I want every terminal transition to be first-writer-wins, so that concurrent stop, failure, and expiry actions remain idempotent.
63. As a developer, I want one live request per Child Profile, so that multiple Guardian Devices cannot create concurrent listening sessions.
64. As a developer, I want each live request bound to its initiating Guardian Device, so that only one authorized listener can negotiate and receive audio.
65. As a developer, I want WebRTC signals authorized by request actor and sender type, so that Guardian and Child cannot publish each other's messages.
66. As a developer, I want one Child offer, one Guardian answer, and bounded ICE candidates, so that a compromised endpoint cannot create unbounded Convex data.
67. As a developer, I want signal publication idempotent, so that network retries cannot duplicate offers, answers, or candidates.
68. As a developer, I want signals to expire no later than their request, so that SDP and network candidate data never outlive its purpose.
69. As a developer, I want configurable STUN URLs and no TURN credentials in this version, so that deployment configuration is explicit and the agreed restrictive-network limitation remains honest.
70. As a developer, I want the demo environment to have a usable STUN default, so that a real peer-to-peer demonstration does not require a relay purchase.
71. As a developer, I want a current pinned native WebRTC artifact behind Cereveil-owned interfaces, so that media implementation is reproducible and replaceable.
72. As a developer, I want a fixed mono Opus speech profile, so that v1 has predictable bandwidth and intelligibility without user-facing quality controls.
73. As a developer, I want existing Child HTTP endpoint contracts preserved, so that authentication work does not unnecessarily rewrite completed features.
74. As a developer, I want Child Device JWTs accepted by Convex realtime, so that Child signaling does not require high-frequency HTTP polling.
75. As a developer, I want ES256 signing separated from Child Device Keystore proof keys, so that backend token issuance and device proof-of-possession remain distinct trust boundaries.
76. As a developer, I want every HTTP and realtime Child operation to validate current credential, enrollment, device, Child Profile, and Household state, so that JWT acceptance alone never grants stale authority.
77. As a developer, I want Remote Audio excluded from Supervision Policy, so that this on-demand Child-confirmed action is not confused with durable offline configuration.
78. As a developer, I want terminal cleanup to delete request, signal, command, and FCM-attempt rows, so that generic messaging retention cannot recreate Remote Audio history.
79. As a developer, I want only a minimal cooldown timestamp retained temporarily, so that cooldown can be enforced without retaining the ended request.
80. As a developer, I want Remote Audio observability limited to anonymous aggregate counters, so that logs and crash systems cannot become shadow session-history stores.

## Implementation Decisions

- Model the complete two-minute lifecycle as a Remote Audio Request. A Remote Audio Session is the connecting and active portion after the Child chooses Start audio.
- Add transient Remote Audio Request, Remote Audio Signal, and Remote Audio Cooldown state to Convex. Do not store terminal request rows.
- Remote Audio Request live states are `awaiting_child`, `connecting`, and `active`.
- Store Household, Child Profile, Child Device, Active Enrollment, initiating Guardian Account, initiating Guardian Device, operation identity, requested time, optional started time, and fixed expiry on the live request.
- Enforce one live request per Child Profile transactionally.
- Require a client-generated operation ID for request creation. Identical retries and repeated taps from the initiating Guardian Device reuse the existing live request; a different Guardian Device receives a stable busy result and cannot take over.
- Creation during cooldown returns a stable cooldown result containing `cooldownUntil`.
- Creation requires an active Guardian Device and full Guardian-to-Household-to-Child ownership, an active Child Profile and Active Enrollment, an active Child Device and credential chain, Online Supervision Health, last-reported microphone and notification capabilities available, and at least one active Child Device FCM token.
- Guardian UI may precompute eligibility from subscriptions, but the creation mutation authoritatively rechecks all prerequisites.
- Keep Remote Audio outside versioned Supervision Policy. It is an on-demand, Child-confirmed capability, not durable offline configuration.
- Create authoritative request state before creating its typed Child Device Command or scheduling FCM delivery.
- Add a typed `request_remote_audio` Child Device Command referencing the Remote Audio Request and sharing its exact expiry.
- Keep FCM payloads generic `child_command` wake-ups. Do not include Child identity, Remote Audio details, SDP, ICE, or session state.
- Child Mode reconciles the command idempotently. It acknowledges the command only after successfully presenting the Start audio/Decline notification, not when audio starts.
- If notification presentation fails, Child Mode terminates the request without capture and marks the command handled. If the request is absent or expired, it handles the command without displaying UI.
- Use a high-importance heads-up request notification with one sound/vibration alert, explicit lock-screen text, and ongoing presentation until Child action, Guardian cancellation, or expiry. Never use a full-screen intent.
- Allow Decline immediately from the lock screen. Require device unlock before Start audio. Keep active Stop immediately available without authentication.
- Start no microphone capture, foreground service, or WebRTC offer before the Child's Start audio interaction.
- Use a microphone foreground service with an ongoing, non-dismissible notification that distinguishes connecting from active media and provides Stop.
- Mirror Remote Audio state and controls inside Child Mode while open, without an accessibility overlay over other apps.
- Make the microphone service `START_NOT_STICKY`. Service or process death releases capture and never reconstructs or resumes a session.
- Child Start audio atomically transitions the live request from awaiting Child to connecting. Only Child Mode may transition connecting to active, after its peer connection reports connected.
- Bind media and signaling to the initiating Guardian Device. Only that device may read/publish its Guardian signals or receive audio. Any active Guardian Device on the same Guardian Account may terminate the request but cannot join or take over.
- Use strictly Child-to-Guardian media. Child publishes one audio track; Guardian is receive-only and never opens its microphone. Do not request `RECORD_AUDIO` in Guardian Mode.
- Provide no talkback, duplex mode, recording, transcript, export, save action, or backend audio path.
- Play Guardian audio through the device speaker only. Do not provide earpiece, wired-headset, Bluetooth, or route controls in v1.
- Use fixed mono Opus at approximately 32 kbps with automatic gain control and noise suppression enabled and acoustic echo cancellation disabled.
- Pin the current selected precompiled Android libwebrtc distribution for both role flavors and hide its types behind Cereveil-owned audio peer interfaces.
- Make Child Mode the SDP offerer only after notification, foreground service, and microphone readiness. The initiating Guardian Device publishes the SDP answer. Both endpoints trickle ICE candidates through Convex.
- Store exactly one Child offer and one Guardian answer per request, each at most 64 KiB.
- Store at most 32 ICE candidates per endpoint, each at most 4 KiB.
- Require client-generated signal idempotency keys and enforce request, sender, and signal-type authorization server-side.
- Do not create signal rows while awaiting the Child. Make signal expiry no later than request expiry.
- Return backend-configured STUN URLs only with authorized live request state. Development and demo may default to Google's public STUN endpoint; production must configure its STUN service explicitly.
- Do not implement TURN, relay credentials, or relay fallback. Restrictive-network failure is an expected v1 outcome.
- Permit WebRTC `DISCONNECTED` to recover to connected within five seconds while the Child notice stays visible and the fixed deadline continues. Treat `FAILED`, `CLOSED`, or five seconds without recovery as terminal. Do not perform ICE restart.
- Treat phone calls, audio-focus loss, microphone contention, and audio-route failure as immediately terminal. Never pause and later resume after an audio interruption.
- Create the Guardian request only from a dedicated visible Remote Audio screen. Leaving, backgrounding, locking, or destroying that screen closes local playback/peer state and terminates the request. Do not add a Guardian playback foreground service.
- Render Guardian screen states for unavailable with reason, ready, awaiting Child, connecting, active, and cooldown. Show countdown and Cancel/Stop controls appropriate to state.
- Keep peer-visible terminal messages generic. Before connection show that the request ended; after connection show that Remote Audio ended. Keep only locally observed reasons in ephemeral endpoint UI.
- Define `expiresAt` as `requestedAt + 2 minutes` across awaiting Child, connecting, and active phases. Never extend it after Child action, WebRTC connection, retry, or network handoff.
- Enforce the deadline independently in Convex, Guardian Mode, and Child Mode. Return `serverNow` with request state and use Android monotonic elapsed time for local countdown and shutdown.
- Make all stop paths local-first. Child Stop disables the audio track, releases the microphone, and removes the foreground-service notification before its mutation. Decline removes the request notification first. Guardian actions close playback and peer state first. Failed terminal mutations never resume media.
- Make terminal transitions first-writer-wins and idempotent. On the first transition, delete the live request, all signals, its Remote Audio command, and associated FCM delivery-attempt rows, then create or update the minimal cooldown row.
- Bypass normal retained terminal-command cleanup for Remote Audio commands.
- Store only `childProfileId` and `cooldownUntil` in the Remote Audio Cooldown row. Do not store a request reference, initiator, outcome, or ended-request timestamps. Delete cooldown state when its three-minute window expires.
- Limit Remote Audio observability to anonymous aggregate counters without request/session identifiers, Child or Guardian identifiers, per-request timestamps, durations, outcomes, SDP, ICE, or payload content.
- Replace Child Device HS256 JWT issuance and manual verification with a clean ES256 cutover because there are no deployed users or legacy tokens.
- Use a dedicated backend P-256 signing key, separate from Child Device Keystore proof-of-possession keys. JWTs include a key ID, subject, URL issuer, audience, issued time, fifteen-minute expiry, and credential/enrollment/device claims.
- Expose the public verification key through JWKS and configure Convex to accept the Child issuer as a custom ES256 JWT provider.
- Keep enrollment and proof-of-possession token refresh on their existing HTTP boundary. Existing Child feature HTTP endpoints retain their request/response contracts and adapt their shared authentication/actor resolution to ES256.
- Add an authenticated Convex Android client for Child realtime Remote Audio state, mutations, and subscriptions.
- Both HTTP and realtime functions must resolve and validate the complete current Child Device authorization chain on every operation; valid token signature and expiry are necessary but insufficient.

## Testing Decisions

- Prefer the highest existing behavioral seams and assert externally visible state and effects rather than helper calls or implementation structure.
- Use the public Guardian Convex query/mutation boundary as the main backend seam for creation, observation, cancellation, stop, eligibility, concurrency, and authorization behavior.
- Use authenticated Child realtime functions plus the existing Child Device command reconciliation boundary as the Child backend seam.
- Extend the existing Convex integration-test harness used by enrollment, commands, Location, Screen Time, access, and Safety work instead of building a separate backend test framework.
- Backend tests cover every creation prerequisite, wrong-Household access, inactive Guardian Device, revoked or mismatched Child authorization chains, Offline state, capability loss, missing FCM token, active request, and cooldown.
- Backend tests cover operation replay, repeated initiating-device taps, concurrent requests from two Guardian Devices, first-writer-wins terminal races, and prevention of listener takeover.
- Backend tests cover awaiting-Child, connecting, and active transitions, including rejection of Guardian attempts to assert Child-owned states and rejection of stale transitions after deletion or expiry.
- Backend tests cover offer/answer sender rules, signal payload caps, candidate count caps, idempotency keys, unauthorized reads, and signal expiry.
- Backend tests use controlled server time to prove the fixed two-minute deadline and three-minute cooldown without wall-clock waiting.
- Backend tests assert complete terminal deletion of request, signals, command, and FCM delivery-attempt rows for Child decline, Child stop, Guardian stop, timeout, connection failure, capability failure, and competing terminal calls.
- Backend tests assert that cooldown contains only the permitted fields and is deleted after expiry.
- Backend tests assert no terminal Remote Audio rows enter generic seven-day messaging retention.
- Backend tests cover ES256 issuance, signature and claim validation, issuer/audience mismatch, expiry, wrong key ID, malformed tokens, revoked credentials, inactive enrollment, mismatched device claims, JWKS output, and authenticated realtime identity resolution.
- Existing Child HTTP integration tests must continue to pass using ES256 for policy, commands, heartbeat, token registration, app catalog, Location, Screen Time, access, and Safety operations.
- Use high-level Guardian and Child Remote Audio coordinator interfaces as the principal Android JVM seam, with fake request/state streams and fake audio-peer adapters. Avoid testing `org.webrtc` internals.
- Guardian JVM tests cover unavailable reasons, request creation, state rendering, countdown derivation, operation replay, Cancel/Stop, generic terminal presentation, local WebRTC failure, screen visibility teardown, and cooldown.
- Child JVM tests cover idempotent command handling, exactly-once notification presentation, stale command handling, Start/Decline, unlock gating state, notice-before-capture ordering, state transitions, local-first Stop, and no restart after process death.
- Timer tests use injected server time and monotonic clocks to prove that wall-clock changes cannot extend request lifetime.
- Peer coordinator tests cover strictly send-only/receive-only negotiation, bounded signal mapping, five-second disconnected recovery, terminal failed/closed states, no ICE restart, audio-focus loss, phone calls, and microphone contention.
- Android API 36 instrumentation tests cover request notification channel behavior, one-time alerting, ongoing presentation, lock-screen-safe Decline, unlock-required Start, microphone foreground-service type/permission startup, active Stop, and service teardown.
- Android instrumentation must prove that failure to post the request notification or start the microphone foreground service produces no capture.
- Compose/UI tests cover the dedicated Guardian screen's unavailable, ready, awaiting, connecting, active, generic ended, and cooldown states and the Child in-app mirrored controls.
- Build and unit-test both Guardian and Child role variants so role-specific permissions and dependencies remain correct; Guardian must not request microphone permission.
- Complete a two-device end-to-end demonstration on API 36-capable Android devices or equivalent test devices. Prove actual Child microphone audio reaches the Guardian speaker, Child Stop is immediate, Guardian leaving the screen ends playback, the two-minute cutoff works, cooldown blocks a new request for three minutes, and Convex retains no Remote Audio request, signal, command, or delivery-attempt rows afterward.
- Treat STUN-only failure on restrictive networks as an expected reported failure, not a test failure when the environment cannot establish a peer-to-peer route. The successful demonstration must use a network topology where STUN-only connectivity is possible.

## Out of Scope

- Automatic microphone start from a background FCM delivery without Child interaction.
- Per-session Child acceptance-free listening.
- TURN servers, relay credentials, relay fallback, SFU/MCU infrastructure, or guaranteed restrictive-network connectivity.
- Guardian-to-Child talkback, duplex calling, Guardian microphone capture, or a phone-call-style UI.
- Audio recording, replay, transcription, speech recognition, export, sharing, download, or local/backend storage.
- Session history, audit history, Guardian-visible initiator history, Child-visible prior-request history, terminal outcome records, or per-session observability.
- Multiple concurrent Guardian listeners, listener handoff, joining from a second Guardian Device, or session takeover.
- Earpiece, Bluetooth, wired-headset, cast, or user-selectable Guardian audio routing.
- User-selectable codec, bitrate, gain, noise suppression, echo cancellation, or audio-quality controls.
- ICE restart or resuming capture after process death, service death, phone call, audio-focus loss, microphone contention, or terminal network failure.
- A Remote Audio enable/disable field in Supervision Policy.
- Accessibility overlays or full-screen intents for Remote Audio disclosure.
- Production migration compatibility for existing HS256 Child Device JWTs; the product has no deployed users.
- Rewriting completed non-Remote-Audio Child HTTP APIs as realtime Convex functions.
- Long-term key rotation workflows beyond using a key ID and a dedicated ES256 signing key for the initial undeployed environment.
- Production analytics dashboards or app-visible aggregate Remote Audio metrics.

## Further Notes

- This PRD deliberately supersedes the earlier automatic-start wording in the disclosed Remote Audio decision because Android API 36 requires a user-visible interaction before an ordinary background consumer app can reliably start microphone capture.
- Remote Audio Request, Remote Audio Session, and Remote Audio Cooldown are distinct canonical concepts. The request begins at Guardian creation; the session is its connecting/active media portion; cooldown is minimal transient enforcement state after any terminal outcome.
- The two-minute promise is an absolute request-lifecycle bound, not two minutes of successfully connected audio. Time awaiting Child action and signaling consumes the same limit.
- “No retained history” includes generic infrastructure. Deleting only feature tables is insufficient if typed commands, FCM delivery attempts, logs, crash metadata, or analytics can reconstruct the request.
- WebRTC provides encrypted peer-to-peer media; Convex contains only short-lived authorization, lifecycle, SDP, and ICE signaling state. The initial STUN-only topology remains best-effort.
- The selected test seams match the existing codebase: Convex public/authenticated boundaries, Child command reconciliation, Android coordinator/runtime adapters, Compose state, and a final two-device behavioral demonstration.
