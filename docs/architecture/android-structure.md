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
