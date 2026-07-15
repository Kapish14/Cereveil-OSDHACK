# Cereveil

> Privacy-first family safety for Android, with scam and NSFW detection running on the Child Device.

> **Install the demo:** Download the ready-to-install Guardian Mode and Child Mode APKs from the [latest GitHub Release](https://github.com/Kapish14/Cereveil-OSDHACK/releases/latest). Install Guardian Mode first, then Child Mode.

Cereveil is one product delivered as two fixed-role Android builds from one codebase:

- **Guardian Mode** lets a Guardian create Child Profiles, enroll a phone, configure protection, review metadata-only safety alerts, request current location and screen time, answer access requests, and start a disclosed live-audio session.
- **Child Mode** visibly enforces the last applied policy, calculates device signals, and runs both AI detectors locally.

The central privacy boundary is simple: **message text and captured screen pixels used by the AI models never leave the Child Device**. Convex receives only the minimum operational data needed to coordinate supervision.

> Submission scope: this documentation describes the `main` branch at the current commit. Other branches are not part of the submission.

## Local AI at a glance

| Detector | Local input | Model and runtime | Local response | Data sent to Guardian/backend |
|---|---|---|---|---|
| Scam text | Visible accessibility text in a selected messaging app | Fine-tuned `ai4bharat/indic-bert`, INT8 ONNX, ONNX Runtime Android | Dismissible scam warning | Type, app package, time, policy version, coarse confidence band |
| NSFW screen | Temporary screenshot/regions from a selected foreground app | Quantized `Marqo/nsfw-image-detection-384`, INT8 ONNX, ONNX Runtime Android | Content-aligned blur overlay | Type, app package, time, policy version, coarse confidence band |

Both models are bundled inside the Child APK and run without a network connection. There is no cloud inference API, remote OCR, screenshot upload, or raw-message upload. Current implementation details and limitations are in [LOCAL_AI_VERIFICATION.md](LOCAL_AI_VERIFICATION.md) and [EVALUATION.md](EVALUATION.md).

Active Screen Safety is intentionally exposed only in **debug/development builds** while accessibility screenshot behavior and distribution-policy compliance are validated. Release builds reject policies that enable these detectors.

### How the real-time interventions work

**Scam warning overlay.** In a Guardian-selected supported messaging app, Child Mode listens for visible window-content changes and debounces repeated events for two seconds. It extracts visible text nodes containing at least 20 characters, suppresses text already present in its in-memory hash cache, and runs the IndicBERT classifier serially on a background dispatcher. A positive fraud label opens a large, generic `SCAM DETECTED` accessibility overlay. The overlay deliberately does not repeat the private message or expose the predicted class/confidence. The Child can dismiss it immediately, and it otherwise closes after 15 seconds. Only metadata is reported to Guardian Mode.

**Real-time NSFW blur.** While an enabled monitored app is in the foreground and the screen is interactive, Child Mode takes temporary accessibility screenshots, discovers visible image-like nodes, crops those regions, and classifies each crop locally. Positive regions are downscaled, repeatedly box-blurred, scaled back to their original size, and placed over the matching accessibility bounds. During scrolling, the overlay follows refreshed node bounds, shifts with scroll deltas, merges newly detected regions, and is removed when the node leaves the viewport, the foreground app changes, or the content no longer remains live. A `Hide for 3s` action lets the Child temporarily dismiss a likely false positive.

The blur is a best-effort visual intervention, **not a 100% perfect censoring boundary**. Accessibility bounds can be incomplete, stale, or unavailable; fast scrolling, animations, video/canvas rendering, secure windows, OEM differences, and inference delay can briefly expose content or leave the blur misaligned. The classifier can also blur safe material such as artwork, sports, medical imagery, memes, or skin-heavy thumbnails. Scam detection likewise can warn on legitimate urgent banking/OTP language or miss obfuscated and image-only scams. Cereveil therefore treats both detectors as assistive signals and expects false positives and false negatives.

## Implemented safety slice

- Five-minute, single-use QR enrollment with a non-exportable Android Keystore device key.
- Versioned policies that are cached and enforced on the Child Device when offline.
- Manual and scheduled app blocking, with temporary access requests.
- Latest-only current location; no location history.
- On-demand, today-so-far per-app screen-time snapshots calculated from Android usage events.
- On-device scam detection for selected WhatsApp, Google Messages, Samsung Messages, or Cereveil messaging surfaces.
- On-device NSFW image-region detection and blur for selected foreground apps.
- Metadata-only Guardian Notices; FCM is only a generic wake-up path.
- Disclosed, Child-stoppable WebRTC live audio; audio is not recorded and is not relayed through Convex.
- End Supervision, device replacement, capability-health reporting, and revocable Guardian/Child device authorization.

## Requirements

- Android Studio with Android SDK 36
- JDK 17
- Node.js and npm compatible with `package-lock.json`
- A Convex deployment
- A Clerk application configured to issue Convex-compatible Guardian JWTs
- Firebase project/mobile app identifiers for both application IDs
- Google Maps API key for Guardian map rendering
- Two Android phones for the complete flow; one phone can host both debug apps for limited development testing
- Google Play services for QR scanning and FCM
- Android 8/API 26 or newer; Android 11/API 30+ is required for NSFW screenshot capture

Debug package names:

```text
com.cereveil.guardian.dev
com.cereveil.child.dev
```

## Setup

### 1. Install dependencies

```bash
git clone <repository-url>
cd Cereveil
npm ci
./gradlew --version
```

### 2. Configure Android public/mobile values

Create an ignored `.env.local` in the repository root:

```properties
CONVEX_URL=https://<deployment>.convex.cloud
CONVEX_SITE_URL=https://<deployment>.convex.site
CLERK_PUBLISHABLE_KEY=pk_<your-publishable-key>
FIREBASE_GUARDIAN_APPLICATION_ID=<guardian-firebase-app-id>
FIREBASE_CHILD_APPLICATION_ID=<child-firebase-app-id>
FIREBASE_API_KEY=<firebase-web-api-key>
FIREBASE_PROJECT_ID=<firebase-project-id>
FIREBASE_GCM_SENDER_ID=<numeric-sender-id>
GOOGLE_MAPS_API_KEY=<android-restricted-maps-key>
```

Do not put `CLERK_SECRET_KEY`, FCM service-account JSON/private keys, Convex child-device signing keys, or other server secrets in Android `BuildConfig`. Restrict the Maps and Firebase keys by Android application ID and signing certificate in their provider consoles.

Provider checklist:

1. In Clerk, create the Guardian application and a JWT template for Convex with audience/application ID `convex`; use its issuer domain as `CLERK_JWT_ISSUER_DOMAIN`.
2. In Firebase, register Android applications for `com.cereveil.guardian.dev` and `com.cereveil.child.dev`, enable Cloud Messaging, and copy their app IDs plus the project API key, project ID, and numeric sender ID into `.env.local`.
3. For backend push delivery, create a narrowly scoped Firebase service account for FCM HTTP v1 and keep its client email/private key only in Convex environment variables.
4. Restrict `GOOGLE_MAPS_API_KEY` to the Guardian package and the debug/release certificate fingerprints that will use it.

### 3. Configure Convex

Link or create the development deployment with a one-shot command. On a fresh checkout, the first push can stop on missing environment values after it has created the deployment; set the values below and rerun it.

```bash
npm run convex:deploy:dev
```

Set the backend environment values declared by `convex/convex.config.ts`:

| Variable | Required | Purpose |
|---|---:|---|
| `CLERK_JWT_ISSUER_DOMAIN` | Yes | Guardian JWT issuer |
| `CHILD_DEVICE_JWT_PRIVATE_JWK` | Yes | ES256 private signing JWK; backend only |
| `CHILD_DEVICE_JWT_PUBLIC_JWK` | Yes | Matching public verification JWK |
| `CHILD_DEVICE_JWT_KEY_ID` | Yes | Key identifier included in Child Device JWTs |
| `CHILD_DEVICE_JWT_ISSUER` | Yes | Public Convex site URL used as the Child Device token issuer/JWKS origin |
| `CHILD_PUSH_TOKEN_ENCRYPTION_SECRET` | Yes | Legacy/fallback FCM token protection secret |
| `FCM_TOKEN_ENCRYPTION_KEY_V1` | Full demo | Active AES-GCM token-encryption key |
| `FCM_TOKEN_ENCRYPTION_ACTIVE_VERSION` | Full demo | Set to `1` when the v1 key is configured |
| `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY` | Full demo | FCM HTTP v1 service-account configuration; required for push delivery |
| `REMOTE_AUDIO_STUN_URLS` | No | Optional comma-separated STUN URLs; no TURN relay is configured |

Generate a fresh ES256 JWK pair locally. Do not paste the private line into chat, source control, or `.env.local`:

```bash
node <<'NODE'
const { generateKeyPairSync, randomUUID } = require('node:crypto');
const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
console.log('CHILD_DEVICE_JWT_KEY_ID=' + randomUUID());
console.log('CHILD_DEVICE_JWT_PRIVATE_JWK=' + JSON.stringify(privateKey.export({ format: 'jwk' })));
console.log('CHILD_DEVICE_JWT_PUBLIC_JWK=' + JSON.stringify(publicKey.export({ format: 'jwk' })));
NODE
```

Example command shape (use real secrets only in your terminal or deployment dashboard):

```bash
npx convex env set CLERK_JWT_ISSUER_DOMAIN 'https://<issuer>'
npx convex env set CHILD_DEVICE_JWT_KEY_ID '<key-id>'
npx convex env set CHILD_DEVICE_JWT_ISSUER 'https://<your-cereveil-issuer>'
npx convex env set CHILD_DEVICE_JWT_PRIVATE_JWK '<private-jwk-json>'
npx convex env set CHILD_DEVICE_JWT_PUBLIC_JWK '<public-jwk-json>'
npx convex env set CHILD_PUSH_TOKEN_ENCRYPTION_SECRET '<random-32-byte-or-longer-secret>'
npx convex env set FCM_TOKEN_ENCRYPTION_KEY_V1 '<different-random-32-byte-or-longer-secret>'
npx convex env set FCM_TOKEN_ENCRYPTION_ACTIVE_VERSION '1'
```

Set `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, and `FCM_PRIVATE_KEY` from the service account for working push delivery. Use the exact multiline private-key value in the Convex dashboard or carefully quoted CLI input; never store the service-account JSON in the repository.

Keep the backend running during local development:

```bash
npm run convex:dev
```

## Build and install both role builds

### Download the ready-to-install APKs

Download the current demo builds from the [latest GitHub Release](https://github.com/Kapish14/Cereveil-OSDHACK/releases/latest):

- [Cereveil Guardian Mode build](https://github.com/Kapish14/Cereveil-OSDHACK/releases/latest/download/Cereveil-Guardian.apk)
- [Cereveil Child Mode build](https://github.com/Kapish14/Cereveil-OSDHACK/releases/latest/download/Cereveil-Child.apk)

These are debug-signed role builds for the project demo. They use the development package names above and keep the development-only Local AI experience available. They are not Play Store release artifacts.

Install them on separate Android devices by downloading each APK and allowing installation from that source. You can also install downloaded files with ADB:

```bash
adb -s <guardian-serial> install -r Cereveil-Guardian.apk
adb -s <child-serial> install -r Cereveil-Child.apk
```

### Rebuild the APKs

Build the two debug APKs from `main`:

```bash
npm run android:assemble
```

To rebuild and stage the stable release assets after an app change:

```bash
npm run android:stage-apks
```

The staged release assets are written to:

```text
app/build/outputs/hackathon/Cereveil-Guardian.apk
app/build/outputs/hackathon/Cereveil-Child.apk
```

The outputs are:

```text
app/build/outputs/apk/guardian/debug/app-guardian-debug.apk
app/build/outputs/apk/child/debug/app-child-debug.apk
```

Install with Android Studio by selecting `guardianDebug` or `childDebug`, or with ADB:

```bash
adb install -r app/build/outputs/apk/guardian/debug/app-guardian-debug.apk
adb install -r app/build/outputs/apk/child/debug/app-child-debug.apk
```

For two physical phones, select the target serial explicitly:

```bash
adb devices -l
adb -s <guardian-serial> install -r app/build/outputs/apk/guardian/debug/app-guardian-debug.apk
adb -s <child-serial> install -r app/build/outputs/apk/child/debug/app-child-debug.apk
```

If an older build was signed with a different certificate, uninstall that package first. This clears its local state:

```bash
adb -s <serial> uninstall com.cereveil.guardian.dev
adb -s <serial> uninstall com.cereveil.child.dev
```

### Correct first-run order

1. Install and open the **Guardian build**. Sign in through Clerk and accept the supervision disclosure.
2. Create a Child Profile using only a display name and birth month/year.
3. Install and open the **Child build** on the Child phone. Complete all seven guided settings with the Child present:
   - enable Cereveil Accessibility;
   - grant Usage Access;
   - grant precise location and microphone;
   - set Location to **Allow all the time** in Android App Info;
   - allow notifications;
   - allow the battery-optimization exemption;
   - enable automatic date/time and automatic time zone.
4. Return to Child Mode until it reports `7 of 7 complete`, then continue to the scanner.
5. In Guardian Mode, generate the enrollment QR for that Child Profile. It expires after five minutes and is single-use.
6. Scan it in Child Mode, verify the displayed Child name on both phones, and confirm enrollment.
7. Wait for Child Mode to show **Protection is on**. In Guardian Mode, check that the desired and applied policy versions agree.
8. For the Local AI demo, use debug builds, open the Child's protection settings in Guardian Mode, enable a detector, and explicitly select a supported monitored app. Keep Accessibility enabled on the Child phone.

On some OEMs, background restrictions are stricter than stock Android. Also allow background activity/autostart for Cereveil Child if the vendor exposes those controls.

## Sample Local AI behavior

Scam input visible in a selected supported messaging app:

```text
KYC expired! Update Aadhaar now or your account will be frozen: bit.ly/verify
```

Expected result: local classification into one of the fraud labels, a Child-facing warning for a positive label, and a metadata-only Guardian alert. The raw sentence is neither stored in Convex nor shown to the Guardian.

Legitimate hard-negative input:

```text
Your OTP is 493827. Valid for 5 minutes. Do not share it with anyone.
```

Expected result: normally `legitimate`/safe, with no intervention. Model outputs are probabilistic; false positives and false negatives remain possible.

NSFW input: display a test image inside an explicitly selected foreground app.

Expected result: positive image regions are covered by an accessibility overlay containing a blurred copy of the region. The screenshot bitmap is transient and is not uploaded or saved by Cereveil.

This result is not guaranteed for every frame or app. A false positive may blur safe content, and the overlay may briefly lag or misalign while content is moving. The Child can use `Hide for 3s` when a blur is clearly incorrect.

### Unfinished live-call scam prototype

A separate `EnfoldAIExpansion` prototype explored detecting scams during phone calls by incrementally transcribing an active device call recording on-device, classifying new transcript segments for fraud, and showing an in-call warning. This feature could not be completed or integrated into Cereveil within the available time and is **not implemented or included in the current APKs**.

## Verify the project

```bash
npm test
npm run typecheck
npm run android:compile
npm run android:assemble
./gradlew :feature:child:ml:test
```

Connected-device model smoke test (optional; not an accuracy benchmark):

```bash
./gradlew :app:connectedChildDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cereveil.child.protection.ChildDeviceSmokeInstrumentedTest#productionSafetyModelsInitializeAndRunOnDevice
```

## Documentation set

- [ARCHITECTURE.md](ARCHITECTURE.md) — system diagram, model pipeline, data flow, and design decisions
- [TECHNICAL_REPORT.md](TECHNICAL_REPORT.md) — model/runtime details, optimization, sizes, compute, and device facts
- [LOCAL_AI_VERIFICATION.md](LOCAL_AI_VERIFICATION.md) — exact on-device/cloud boundary
- [EVALUATION.md](EVALUATION.md) — retained quality evidence, methodology, baselines, and failure cases
- [PRIVACY_AND_SAFETY.md](PRIVACY_AND_SAFETY.md) — permissions, storage, retention, risks, and mitigations
- [ATTRIBUTION.md](ATTRIBUTION.md) — models, datasets, libraries, APIs, and pre-existing work
- [CONTEXT.md](CONTEXT.md) and [docs/adr](docs/adr) — domain vocabulary and detailed decisions
