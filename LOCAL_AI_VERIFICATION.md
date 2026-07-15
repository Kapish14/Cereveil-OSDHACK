# Local AI Verification

## Verification statement

The scam-text and NSFW-screen models are packaged in `feature/child/ml/src/main/assets/models` and instantiated by `feature/child/ml` through ONNX Runtime Android. Neither classifier has a network client, cloud endpoint, API key, or server fallback.

## What runs fully on the Child Device

- Accessibility event filtering and monitored-package checks.
- Visible text extraction, hashing/deduplication, IndicBERT tokenization, and fraud inference.
- Accessibility screenshot capture, candidate-region cropping, 384×384 preprocessing, and NSFW inference.
- Scam warning and content-aligned blur rendering.
- Confidence-band reduction before incident reporting.
- App/schedule enforcement and local access-grant checks.
- Today-so-far screen-time aggregation from Android usage events.
- Validation and caching of the last applied policy.

The two models can infer while the device is offline after their policy and monitored-app selection have already been received.

## What requires internet

- Clerk Guardian sign-in and account bootstrap.
- Creating/previewing/consuming an enrollment QR.
- Initial policy download and later policy changes.
- Uploading metadata-only safety incidents and receiving Guardian Notices.
- Access request approval/denial.
- Updating latest location and requested screen-time snapshots.
- FCM delivery and Convex realtime subscriptions.
- Remote-audio request/signaling and the live WebRTC connection.

If safety metadata cannot upload, Child Mode stores a bounded queue of at most 200 metadata-only incidents for up to seven days and retries. Raw text and pixels are not queued.

## Does user data leave the device?

Some operational data does; AI source content does not.

| Data | Leaves Child Device? | Destination/purpose |
|---|---:|---|
| Visible message/accessibility text | No | Memory-only Local AI input |
| Accessibility screenshot/cropped pixels | No | Transient Local AI and blur input |
| OCR output or screen recording | Not produced | Not applicable |
| Safety alert metadata | Yes | Convex and authenticated Guardian Mode view |
| Launchable app package names and labels | Yes | Guardian Mode selection/catalog |
| Today-so-far per-app durations | Yes, on request | Convex/Guardian current snapshot |
| Current latitude/longitude/accuracy/time | Yes, when enabled | Convex/Guardian latest-location view |
| Microphone audio during accepted live session | Yes | WebRTC media path to Guardian Device; not Convex storage |
| FCM token | Yes | Encrypted in Convex for push delivery |
| Cached policy/device credential state | Normally no | Local app storage/Android Keystore |

## Code-level evidence

- `OnDeviceScamTextClassifier` opens `models/fraud-classifier/model_int8.onnx` from Android assets and calls `OrtSession.run` locally.
- `OnDeviceNsfwImageClassifier` opens `models/nsfw/model_int8.onnx`, transforms pixels locally, and calls `OrtSession.run` locally.
- `ChildSafetyAlertReporter` serializes only incident ID, type, package, confidence band, policy version, and time.
- Convex `safetyAlerts` schema has no raw-text, screenshot, image, OCR, or content field.
- FCM is used as a wake-up channel; clients fetch authorized state from Convex.

## Reproduce the verification

1. Build and install `childDebug`.
2. Put the phone in airplane mode after a detector policy has been applied.
3. Present a known test message/image in a selected app.
4. Observe the local warning/blur.
5. Confirm the Guardian receives nothing while offline.
6. Restore the network and confirm only detector metadata appears.
7. Inspect Convex documents/logs: there should be no raw message or screenshot field.

The existing `productionSafetyModelsInitializeAndRunOnDevice` connected test additionally verifies that the packaged classifiers initialize and produce finite outputs.

## Scope qualification

Active Screen Safety is enabled only in debug/development builds on the current `main` branch. This limits distribution readiness but does not change where inference executes.
