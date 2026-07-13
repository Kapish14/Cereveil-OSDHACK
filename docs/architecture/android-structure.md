# Android Structure

This document records the intended Android project shape and implementation tooling notes for Cereveil. ADR-0041 records the architectural decision to separate role and core modules; this document captures the working structure we intend to implement.

## Intended module shape

```text
:app
  Production single-app entry point.

:guardianApp
  Development and hackathon Guardian-only APK shell.

:childApp
  Development and hackathon Child-only APK shell.

:core:domain
  Shared plain-Kotlin domain models, IDs, validation, and product rules.

:core:network
  Convex client setup, auth token providers, FCM token registration, and backend API wrappers.

:core:database
  Room setup and shared persistence infrastructure.

:core:ui
  Shared Compose theme, design tokens, and non-critical reusable UI components.

:guardian
  Guardian Mode screens and flows.

:child
  Child Mode screens and flows.

:child:protection
  Accessibility, Usage Access, VPN, location, notification capability checks, foreground services, and enforcement integration.

:feature:child:ml
  On-device Scam Text Detection and NSFW Screen Detection model wrappers.
```

## Role separation

Production uses one Cereveil app with role selection and Role Lock.

Development and hackathon builds may use separate thin Guardian and Child APK shells so both roles can be built, installed, and demonstrated more quickly.

## Core module rules

`:core:domain` should stay plain Kotlin where possible.

It should not depend on:

- Android UI;
- Convex SDK;
- Clerk;
- Firebase;
- Room.

`:core:network` may depend on Convex, Clerk, Firebase/FCM, and the auth token provider abstractions.

`:core:ui` may use Compose and approved reusable UI components, but safety-critical Child enforcement UI should remain simple and app-owned.

## Auth token providers

Guardian and Child Mode use different auth mechanisms behind a shared interface:

```kotlin
interface AuthTokenProvider {
    suspend fun getToken(): String?
}
```

Expected adapters:

```text
ClerkGuardianAuthTokenProvider
ChildDeviceAuthTokenProvider
```

Rules:

- Clerk should not leak into Child Mode.
- Child Device JWT handling should not leak into Guardian Mode.
- The Convex client should depend on the token provider interface, not a concrete auth provider.

## Enforcement UI

Child enforcement UI includes:

- Block Screen;
- Safety Intervention overlays;
- Remote Audio persistent notice;
- Protection Degraded warning;
- offline Access Request message.

These screens should be minimal, predictable, and owned by Cereveil rather than heavily depending on external UI kits.

The Remote Audio persistent notice first presents Start audio and Decline actions without capturing audio. The Child's Start audio interaction launches the microphone foreground service; its ongoing, non-dismissible notification then distinguishes connecting from active media and provides a direct Stop action. Child Mode mirrors the same status and controls while open, but does not use an accessibility overlay over other apps. If the request notification cannot be presented or the microphone foreground service cannot be launched, Child Mode terminates the request without capturing audio.

The request notification may appear on the lock screen. Decline works immediately, Start audio requires device unlock, and the active-session Stop action remains immediately available without authentication.

An incoming Remote Audio Request uses a high-importance heads-up notification with explicit lock-screen text, “Your guardian is requesting remote audio.” It is ongoing until Child action, Guardian cancellation, or expiry, alerts with sound/vibration once without repetition, and never uses a full-screen intent.

Both Guardian and Child role flavors use the pinned `io.github.webrtc-sdk:android:144.7559.09` artifact behind Cereveil-owned audio peer interfaces. Remote Audio uses only the required audio and peer-connection APIs; feature code does not expose `org.webrtc` types outside the adapter boundary so the precompiled libwebrtc distribution remains replaceable.

Guardian Mode plays Remote Audio through the device speaker only in this version. It does not offer an earpiece, wired-headset, Bluetooth, or user-selectable route control.

WebRTC media is strictly Child send-only and Guardian receive-only. Guardian Mode never opens its microphone and its role flavor does not request `RECORD_AUDIO`; neither role exposes talkback, duplex audio, recording, transcription, or saving.

The v1 audio profile is fixed mono Opus at approximately 32 kbps, with WebRTC automatic gain control and noise suppression enabled and acoustic echo cancellation disabled because Child Mode receives no return audio. Neither endpoint exposes quality or codec controls.

The initiating Guardian Device keeps a Remote Audio Request alive only while its dedicated Remote Audio screen is visibly foregrounded. Navigating away, backgrounding Guardian Mode, locking the device, or destroying that screen terminates the request and starts cooldown; Guardian Mode does not run a playback foreground service.

Guardian Mode opens that dedicated screen from the Child's Live Features surface and creates a request only after the screen is visible. The screen renders authoritative eligibility as unavailable with a reason, ready with Request audio, awaiting Child with countdown and Cancel, connecting with countdown and Stop, active with countdown and Stop, or cooldown with the next available time.

Either endpoint treats phone-call interruption, audio-focus loss, microphone contention, and audio-route failure as terminal. Remote Audio does not pause or resume after an interruption; Convex ends the request and applies cooldown, and a later attempt requires a new Remote Audio Request and Child Start audio action.

All stop paths are local-first. Child Stop disables the audio track, releases the microphone, and removes its foreground-service notification before the terminal mutation; Decline removes the request notification first. Guardian Stop, Cancel, navigation away, or backgrounding closes playback and the peer connection before its mutation. Network failure never resumes local media, and the backend request still expires at its fixed deadline.

Both endpoints independently enforce the backend-owned `expiresAt`. They derive remaining time from the request's accompanying `serverNow` and schedule local shutdown using Android monotonic elapsed time, so wall-clock or time-zone changes cannot extend capture or playback.

The Child microphone foreground service is `START_NOT_STICKY`. Service destruction or process death releases capture immediately and never reconstructs or resumes a Remote Audio Session after restart. The next authenticated reconciliation terminates any backend request that remains live; otherwise Convex expiry removes it. Capture after restart always requires a fresh request and fresh Child Start audio action.

When the subscribed Remote Audio Request disappears, a peer that did not initiate or directly observe the terminal action shows only a generic message: before connection, “Remote audio request ended”; after connection, “Remote audio ended.” Specific terminal reasons remain local to the endpoint that observed them and are not persisted or delivered through a terminal backend row.

## Child ML module

`:feature:child:ml` owns the Child-only ONNX Runtime dependency, quantized model and tokenizer assets, one process-wide runtime environment, classifier sessions, preprocessing, predictions, model-version sensitivity mappings, and model tests. Schema v3 initializes and self-checks only the enabled detector sessions before policy acknowledgement, keeps each loaded while its section remains enabled, and releases it on section disablement or End Supervision without closing the process-wide environment from an individual classifier. Child Mode owns Accessibility orchestration, detector-specific Monitored App gating, monitored-window and text extraction, Safety Interventions, Safety Incident suppression, and Safety Alert delivery; each detector idles whenever none of its own Monitored Apps is visible. Guardian builds do not depend on or package the ML module.

## Android CLI tooling note

Implementation may use the locally installed Android command-line tooling when available, including:

```text
sdkmanager
avdmanager
emulator
adb
gradle / ./gradlew
```

Use the Android CLI for practical implementation work such as:

- creating or inspecting emulator devices;
- checking installed SDK/platform/build-tools versions;
- building debug and release variants;
- installing Guardian/Child dev APKs;
- running instrumented tests;
- collecting `adb logcat` output during debugging.

Before relying on CLI tooling, verify availability in the local environment with commands such as:

```sh
which adb
which sdkmanager
which avdmanager
which emulator
./gradlew --version
```

Android Studio can still be used for interactive development; the CLI note only means implementation agents are allowed to use local Android command-line tooling when it is present and useful.
