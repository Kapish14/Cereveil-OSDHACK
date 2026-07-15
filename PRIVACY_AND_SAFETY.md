# Privacy and Safety

## Data-minimization principles

- Child Profile identity is limited to a display name and birth month/year in the current user flow; no avatar is currently collected.
- AI source content is processed locally and is not uploaded.
- Location is latest-only rather than historical.
- Screen time is an on-demand aggregate, not raw usage-event history.
- Safety alerts contain detector metadata, not the message or image.
- Remote audio is disclosed, Child-stoppable, and never recorded by Cereveil.
- Ending supervision revokes Child authority and deletes linked Child data, leaving only unlinkable revocation proof material needed to inform an offline former device.

## Android permissions and purpose

| Permission/setting | Role | Purpose and risk |
|---|---|---|
| Accessibility Service | Child | App enforcement, visible text access, screenshots, and overlays. Highly sensitive; can observe foreground UI. |
| Usage Access | Child | Launchable app catalog and today-so-far screen-time aggregation. Reveals app-use patterns. |
| Fine/coarse/background location | Child | Latest location and on-demand refresh. Reveals current physical location. |
| Microphone + foreground service | Child | Disclosed live audio after a Guardian request. Captures ambient sound during the active session. |
| Notifications | Both | Visible status, notices, and remote-audio/location disclosure. |
| Ignore battery optimization | Child | Improves background reconciliation/reliability; may increase battery use. |
| Network state/internet via libraries | Both | Convex, Clerk, FCM, map and WebRTC coordination. |

Automatic date/time is required as a trust condition for schedules and day-bound screen-time calculations, but it is a system setting rather than an app permission.

## Storage and retention

### Child Device

- Android Keystore stores a non-exportable ES256 private key.
- SharedPreferences store enrollment/device tokens, the cached complete policy, local access grants, and bounded pending alert metadata.
- Pending safety metadata is capped at 200 incidents and seven days.
- Raw AI text is transient; duplicate hashes are memory-only.
- Screenshot/crop bitmaps are transient inference/blur inputs and are not intentionally persisted.

### Convex

- Enrollment codes are stored hashed and expire after five minutes.
- Child JWTs are short-lived (15 minutes) and refreshed through signed challenges.
- FCM tokens are encrypted before storage when active encryption is configured.
- Safety alerts contain only incident ID, type, app package, coarse confidence, policy version, and timestamps, and expire after seven days.
- Location uses a single latest-state row per enrollment; no route/history table exists.
- Screen-time snapshots replace/supersede earlier snapshots rather than ingesting raw events.
- Remote-audio signals and request state expire; audio is never stored in Convex.

## Network disclosure

Raw text and pixels do not leave the device. The following can leave when their features are enabled: app catalog, current screen-time aggregates, latest location, operational/authentication identifiers, safety metadata, FCM tokens, and WebRTC audio sent to the Guardian peer during an accepted session.

FCM notification text is generic. Sensitive detail is revealed only after authenticated Guardian Mode fetches authorized Convex state.

## Security controls

- Guardian operations require Clerk identity plus an active Guardian Device binding.
- Child operations validate the credential, active enrollment, Child Device, household/resource chain, and short-lived JWT.
- QR tokens are random, short-lived, single-use, and stored only as hashes.
- Policies are versioned, complete, validated, and acknowledged after local application.
- Operation IDs and incident IDs make retries idempotent.
- Backend functions validate actor/resource ownership; Convex is authoritative over push payloads.
- Debug Local AI is rejected by the release policy runtime.

## Secret-handling audit

The current `main` working tree and reachable `main` history were scanned for common high-confidence credential formats and tracked sensitive filenames. No tracked `.env`, `local.properties`, `google-services.json`, keystore/private-key file, or high-confidence secret pattern was found. `.env.local` is ignored and must remain untracked.

Mobile publishable/API identifiers are not equivalent to backend secrets, but they must still be provider-restricted. Never compile Clerk secret keys, Child JWT private JWKs, FCM service-account private keys, or unrestricted Maps keys into an APK.

This is a focused repository audit, not a guarantee against every possible encoded or third-party secret. Before public submission, also verify the hosting platform's secret scanner and rotate any credential ever shared outside its intended secret store.

## Limitations and risks

- Accessibility and Usage Access are powerful capabilities; a bug or malicious update could expose sensitive UI/app-use data.
- Location and remote audio can create coercion or surveillance risk even with disclosure.
- Model errors can produce distress, conceal legitimate content, or miss harmful content.
- Guardian access is household-wide and should be limited to trusted devices.
- OEM background-management and accessibility behavior can weaken reliability.
- The shared manifest currently permits Android backup; sensitive local preferences need explicit backup exclusion or `allowBackup=false` before production release.
- No app can guarantee uninstall prevention on consumer Android; Cereveil reports capability loss where possible.
- Current release builds are not ready to ship Active Screen Safety.

## Safety posture

Cereveil is designed for transparent, age-appropriate supervision with the Child's awareness. It is not designed for covert monitoring, evidence collection, policing intent, emergency response, or unsupervised automated punishment. Guardians should discuss alerts with the Child and independently verify urgent safety concerns.
