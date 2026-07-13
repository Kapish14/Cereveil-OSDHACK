# Remote Audio verification record

Date: 2026-07-14  
Target/compile SDK: Android 36  
WebRTC: `io.github.webrtc-sdk:android:144.7559.09`  
Network design: STUN-only; development default `stun:stun.l.google.com:19302`

## Automated checks completed

- Convex TypeScript typecheck and all 49 backend tests pass.
- Public Guardian and authenticated Child tests cover request creation, fixed deadline, retry idempotency, installation binding, Child-owned transitions, bounded/idempotent signaling, active transition, terminal deletion, cooldown, and scheduled cooldown deletion.
- Guardian and Child unit-test variants pass, including monotonic deadline and local-first idempotent shutdown tests.
- Both Guardian and Child debug APKs assemble against Android 36 with the pinned native WebRTC artifact.
- The merged Guardian manifest contains no microphone or microphone-foreground-service permission.
- The merged Child manifest contains `RECORD_AUDIO`, `FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_MICROPHONE`, and declares the non-exported microphone foreground service.
- Neither merged manifest contains `USE_FULL_SCREEN_INTENT`.

## Environment-limited checks

No Android device or AVD is attached in this workspace. API 36 notification/foreground-service instrumentation and the real two-device live-audio demonstration therefore remain physical-environment verification steps. This is not represented as a successful device run.

For the manual run, use one Guardian and one Child Android 36 device on a topology that permits direct WebRTC connectivity. Verify consent, speaker playback, Child Stop, Guardian-screen teardown, fixed expiry, cooldown, and post-session Convex table deletion. A STUN-only failure on restrictive NAT is expected; no TURN fallback exists in v1.
