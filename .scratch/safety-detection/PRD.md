Status: ready-for-agent

# Active Screen Safety Detection

## Problem Statement

Cereveil has completed the core Guardian/Child supervision foundations, including enrollment, authenticated policy delivery, desired-versus-applied policy state, Accessibility-based enforcement, latest App Catalog synchronization, generic FCM wake delivery, and Guardian Notice reconciliation. It does not yet protect a Child when visible content in a selected app appears scam-like or sexually explicit.

Two existing prototype applications prove the main on-device techniques. EnfoldAIExpansion contains the Child owner's eight-class quantized BERT/ONNX scam-text classifier, tokenizer, and warning overlay. Revive contains a quantized Marqo/ONNX NSFW image classifier, Accessibility screenshot scanning, relevant-window cropping, and real-time blur behavior. Those prototypes are useful sources, but their services, ownership assumptions, hard-coded app rules, storage, and UI cannot be copied as product architecture. Cereveil needs narrow detector interfaces, its own policy lifecycle, strict window scoping, metadata-only reporting, privacy-bounded retention, and authoritative multi-device Guardian notification delivery.

The two risks also require different behavior. A suspected scam should show the Child a generic, dismissible warning. Suspected NSFW content should be blurred immediately without a warning, message, or bypass. Repeated detections must not bombard Guardians: a short-lived item fingerprint prevents the same visible item from becoming repeated incidents, while an independent backend cooldown limits immediate notifications for novel scam and NSFW incidents. Neither mechanism may suppress the local safety intervention that protects the Child.

The feature is development-only in this phase. Google Play child-monitoring declarations, prominent disclosure and consent, a persistent monitoring notification, listing language, permissions declarations, and Data Safety work remain required before any Play-distributed build enables Active Screen Safety.

## Solution

Add Active Screen Safety to Supervision Policy schema v3 as two independent sections: Scam Text Detection and NSFW Screen Detection. Each section has its own enabled state, explicit set of monitored package names, and Guardian-facing Lower, Standard, or Higher sensitivity. App choices come from the latest Child App Catalog, remain selected while a section is disabled or an app is temporarily uninstalled, and are never expanded automatically after a new installation.

Create a Child-only machine-learning feature module containing replaceable scam-text and NSFW-image classifier interfaces, a single process-wide ONNX environment, the Enfold eight-class quantized model and tokenizer, and only Revive's quantized NSFW model. Child Mode initializes and self-checks every detector enabled by a candidate policy before acknowledging that complete policy. Any required-model failure rejects the complete candidate and preserves the previously accepted policy.

Extend the existing Cereveil Accessibility service rather than introducing competing services. Scam scanning considers individual visible, non-editable text nodes from a monitored foreground app after a short per-package debounce. NSFW scanning reuses Revive's adaptive screenshot cadence, relevant image-node tracking, and blur lifecycle, but strictly crops to the monitored app's window. When no usable image node exists, it may classify that monitored app window and blur that window after a positive result. Scanning stops when the relevant section is disabled or its monitored window is absent, and NSFW capture also stops while the screen is off or locked.

Represent every novel positive result as one Safety Incident. It produces one local Safety Intervention and one metadata-only Safety Alert. A memory-only ten-minute fingerprint suppresses repeat incidents for the same scam text or visually equivalent NSFW crop in the same package. Fingerprints never leave the Child Device and reset on process restart, detector disablement, or End Supervision.

Upload Safety Alerts through authenticated Child APIs to Convex. The backend validates the active enrollment and applied policy, derives ownership identifiers, stores the alert idempotently, and retains it for one week. No raw text, screenshot, crop, OCR, tokenizer input, fingerprint, fraud subtype, or summary is uploaded. If the alert arrives within five minutes of occurrence and its Child-and-detection-type notification cooldown is open, Convex transactionally creates one Guardian Notice and begins a fixed two-minute cooldown. It then sends a generic high-priority FCM wake-up to every active Guardian Device. FCM carries no incident details and is never authoritative; Guardian Mode reconciles notices and alerts from Convex.

## User Stories

1. As a Guardian, I want one Active Screen Safety page with separate Scam Text Detection and NSFW Screen Detection sections, so that I can understand and configure each protection independently.
2. As a Guardian, I want each detector to have its own enabled state, so that I can use one without enabling the other.
3. As a Guardian, I want each detector to have its own monitored-app selection, so that text-heavy and image-heavy risks can be scoped differently.
4. As a Guardian, I want the same package selectable for both detectors, so that one app can receive both kinds of protection.
5. As a Guardian, I want app selection based on the latest Child App Catalog, so that I choose recognizable installed apps rather than type package names.
6. As a Guardian, I want to search and multi-select monitored apps independently in each section, so that managing a longer catalog remains practical.
7. As a Guardian, I want suggested apps to remain unchecked until I explicitly select them, so that Cereveil never broadens monitoring silently.
8. As a Guardian, I do not want newly installed apps added automatically, so that monitoring scope changes only through an intentional policy edit.
9. As a Guardian, I want a package selection to survive temporary uninstall and reinstall, so that reinstalling an app does not erase my intent.
10. As a Guardian, I want disabling a detector to preserve its selected apps and sensitivity, so that temporary suspension is reversible.
11. As a Guardian, I want an enabled detector to require at least one monitored package, so that enabled-but-inert policy cannot be saved.
12. As a Guardian, I want Lower, Standard, and Higher sensitivity choices for each detector, so that I can choose a comprehensible tradeoff without managing raw model thresholds.
13. As a Guardian, I want sensitivity to apply to all apps selected for that detector, so that the initial settings remain understandable.
14. As a Guardian, I want changes to use the existing pending, applied, waiting, and failed policy lifecycle, so that a backend save is not mistaken for active Child protection.
15. As a Guardian, I want the complete Active Screen Safety configuration applied atomically with the rest of the policy, so that the Child never runs a partially accepted snapshot.
16. As a Guardian, I want Android 8 through 10 Child Devices to offer Scam Text Detection but mark NSFW Screen Detection unavailable, so that supported protection is not unnecessarily withheld.
17. As a Guardian, I want Android 11 or later required for NSFW Screen Detection, so that the policy does not promise screenshot APIs the Child cannot use.
18. As a Child, I want schema-v1 and schema-v2 policies migrated to both detectors disabled, empty monitored-app lists, and Standard sensitivity, so that an upgrade never starts monitoring implicitly.
19. As a Child, I want my reported maximum supported policy schema to govern Guardian editing, so that the backend refuses schema-v3 changes when I cannot apply them.
20. As a Child, I want enabled models initialized and self-checked before policy acknowledgement, so that applied status means the detector can actually run.
21. As a Child, I want failure of either enabled detector to reject the complete candidate policy, so that atomic policy truth is preserved.
22. As a Child, I want the previously accepted policy retained after a candidate failure, so that an update cannot silently weaken working protection.
23. As a Child, I want enabled model sessions kept ready while supervision runs, so that detection does not pay model startup cost for every event.
24. As a Child, I want a detector's model session released after that section is disabled or supervision ends, so that unused memory is reclaimed.
25. As a Child, I want one process-wide ONNX environment shared by the model sessions, so that native runtime ownership is safe and predictable.
26. As a Child, I want the ML assets packaged only in Child builds, so that Guardian builds do not carry roughly 81 MB of unused private models.
27. As a Child, I want Scam Text Detection to inspect only an explicitly monitored foreground package, so that other apps are outside its scope.
28. As a Child, I want only visible, non-editable individual text nodes considered, so that hidden content and message drafts are not classified.
29. As a Child, I do not want editable fields, password fields, Cereveil overlays, obvious timestamps, or obvious app chrome classified, so that common false inputs are excluded.
30. As a Child, I want scam scanning debounced for two seconds per foreground package, so that rapid Accessibility events do not classify the same screen continuously.
31. As a Child, I want normalized candidates shorter than 20 characters ignored, so that tiny labels do not generate weak detections.
32. As a Child, I want candidates classified individually rather than concatenating a whole screen or conversation, so that unrelated messages do not become one model input.
33. As a Child, I want the existing tokenizer semantics and 128-token truncation retained, so that the deployed model sees the input format it was trained to accept.
34. As a Child, I want sent and received visible messages evaluated without sender-direction inference, so that protection does not depend on unreliable UI interpretation.
35. As a Child, I do not want OCR, notification scraping, background message databases, or conversation history added, so that text monitoring remains visible-screen-only.
36. As a Child, I want the Enfold eight-class trained model to treat classes 2 through 7 as scam and legitimate plus non-financial-spam as non-scam, so that Cereveil preserves the owner's deployed classifier semantics.
37. As a Child, I want Standard scam sensitivity to preserve Enfold's top-class behavior with no additional probability floor, so that the initial migration has a known reference point.
38. As a Child, I want a suspected scam to show a generic warning that does not reveal raw text, subtype, or confidence, so that the intervention protects without repeating sensitive content.
39. As a Child, I want the scam warning to advise against sharing passwords, OTPs, or payment details, so that it gives a useful next action.
40. As a Child, I want to dismiss the scam warning with “I understand,” so that it does not permanently block legitimate use.
41. As a Child, I want the scam warning to auto-dismiss after 15 seconds, so that it cannot strand me if I take no action.
42. As a Child, I want NSFW Screen Detection to inspect only explicitly monitored visible app windows, so that content from another app is never captured as part of the selected app.
43. As a Child, I want system bars, keyboards, Cereveil overlays, and adjacent split-screen windows excluded from screenshot crops, so that classification remains narrowly scoped.
44. As a Child, I want NSFW scanning to reuse Revive's relevant-image-node tracking and adaptive cadence, so that known real-time behavior is retained.
45. As a Child, I want the active-touch scan cadence to default to one attempt every 200 milliseconds for a two-second activity window, so that newly revealed media is handled quickly.
46. As a Child, I want idle NSFW scanning to default to one attempt per second, so that protection remains responsive without continuous maximum-rate capture.
47. As a Child, I want at most one screenshot capture in flight, so that Accessibility capture work cannot pile up.
48. As a Child, I want image-node caches, scroll handling, watchdog behavior, and blur-hold defaults copied from Revive as app-owned constants, so that they are not exposed as Guardian tuning controls.
49. As a Child, I want scanning to stop entirely when the screen is off, the device is locked, NSFW detection is disabled, or its monitored window disappears, so that capture runs only when useful.
50. As a Child, I want split-screen and picture-in-picture supported when the monitored app owns a visible window, so that alternate window modes do not bypass protection.
51. As a Child, I want a usable image-node crop classified when available, so that the blur can target the relevant content.
52. As a Child, I want the monitored app window classified when no usable image node exists, so that full-window media such as video remains best-effort protected.
53. As a Child, I want only that monitored app window blurred after a positive fallback classification, so that unrelated visible applications remain usable.
54. As a Child, I want Standard NSFW sensitivity to preserve Revive's sensitivity-60 mapping, equivalent to a 0.40 positive-confidence threshold, so that initial behavior has a known baseline.
55. As a Child, I want positive NSFW content blurred without any dialog, banner, toast, or textual warning, so that the intervention is immediate and discreet.
56. As a Child, I do not want a dismiss, false-positive, or snooze control on NSFW blur, so that visible positive content remains obscured.
57. As a Child, I want blur retained while the positive content remains visible and removed when content is replaced, the app window leaves, or a safe recheck succeeds, so that the overlay follows current content.
58. As a Child, I accept best-effort detection where Android or an app blocks Accessibility inspection or screenshots, so that Cereveil reports real platform limits rather than claiming impossible coverage.
59. As a Guardian, I want any user-launchable package in the App Catalog eligible for explicit selection, so that detection is not limited by Enfold's prototype allowlist.
60. As a Child, I want one novel positive item to create one Safety Incident, one local Safety Intervention, and one metadata-only Safety Alert, so that product behavior has a clear unit.
61. As a Child, I want the same normalized scam text in the same package suppressed for ten minutes, so that a stationary message does not repeat warnings and alerts.
62. As a Child, I want a visually equivalent NSFW crop in the same package suppressed for ten minutes using a perceptual fingerprint, so that minor capture differences do not create repeated incidents.
63. As a Child, I want scam and NSFW fingerprints independent, so that one risk type never suppresses the other.
64. As a Child, I want fingerprints kept only in memory and never uploaded, so that deduplication does not create sensitive backend identifiers.
65. As a Child, I want fingerprint state cleared on process restart, detector disablement, and End Supervision, so that it has a bounded lifecycle.
66. As a Guardian, I accept one repeated incident after a Child process restart, so that deduplication needs no persistent sensitive state.
67. As a Guardian, I want each Safety Alert assigned a random incident identifier and associated with the active enrollment and applied policy, so that uploads are attributable and idempotent.
68. As a Guardian, I want an alert to contain only its type, package and resolved app label, confidence band, policy version, occurrence time, creation time, and expiry, so that I receive useful context without captured content.
69. As a Child, I do not want raw text, pixels, screenshots, OCR, model input, fingerprints, fraud subtypes, or raw model scores uploaded, logged, or retained, so that detection remains privacy bounded.
70. As a Child, I want the backend to derive ownership and device identity from my authenticated active enrollment, so that uploads cannot claim another Child's identity.
71. As a Child, I want the backend to verify the relevant detector was applied for the reported package, so that stale or fabricated client alerts are rejected.
72. As a Child, I want duplicate uploads with the same enrollment and incident identifier to converge on one alert, so that retries are safe.
73. As a Child, I want an offline metadata-only alert queued locally for at most seven days, so that temporary disconnection does not require storing captured content.
74. As a Child, I want queued alerts retried idempotently after reconnecting, so that eventual delivery cannot duplicate records.
75. As a Guardian, I want an alert arriving more than five minutes after it occurred stored in the feed but excluded from immediate notification, so that stale offline events do not wake me as if they are current.
76. As a Guardian, I want Safety Alerts automatically deleted after one week, so that the backend does not accumulate a long-term behavioral history.
77. As a Guardian, I do not want manual retention, export, sharing, or search features for Safety Alerts, so that the first version stays narrowly safety oriented.
78. As a Guardian, I do not want daily, weekly, periodic, or incident Safety Summaries, so that Cereveil retains and presents only individual one-week alerts.
79. As a Guardian, I want immediate-notification cooldowns keyed independently by Child Profile and detection type, so that scam alerts do not suppress NSFW alerts and one Child does not suppress another.
80. As a Guardian, I want each cooldown to default to a fixed two minutes, so that novel incidents do not bombard my devices.
81. As a Guardian, I want an alert during cooldown still stored and shown in the safety feed, so that notification suppression does not erase the event.
82. As a Child, I want every novel detection to receive its local intervention even during Guardian notification cooldown, so that remote notification policy never weakens immediate protection.
83. As a Guardian, I want a suppressed incident not to extend the cooldown, so that the window is fixed rather than sliding indefinitely.
84. As a Guardian, I do not want a delayed notification emitted merely because cooldown later expires, so that only timely incident processing creates a wake-up.
85. As a Guardian, I want Convex to check cooldown and create the Guardian Notice transactionally with alert processing, so that concurrent uploads cannot bypass the limit.
86. As a Guardian, I want the Child to upload alerts rather than send FCM directly, so that authorization and notification policy remain backend authoritative.
87. As a Guardian, I want every active Guardian Device to receive a high-priority generic FCM wake-up for an allowed Notice, so that either authorized device can reconcile promptly.
88. As a Guardian, I want FCM payloads to omit Child identity, detection type, app, confidence, and captured content, so that lock-screen and transport metadata remain generic.
89. As a Guardian, I want the local notification to say only that a named Child has a new safety alert and invite me to open Cereveil, so that sensitive incident details stay behind authentication.
90. As a Guardian, I want tapping the notification to open the selected Child's safety feed, so that I can review authoritative details after authentication.
91. As a Guardian, I want Guardian Mode to reconcile missed, duplicated, and out-of-order Notice wake-ups from Convex, so that FCM delivery is never treated as authoritative state.
92. As a Guardian, I want opening an approved Notice to mark it read through the existing Notice lifecycle, so that my devices converge on review state.
93. As a Guardian, I accept that cooldown-suppressed alerts have no Notice and no FCM, so that the safety feed rather than a delayed push is their discovery path.
94. As a Guardian, I want a newest-first per-Child safety feed showing type, app, occurrence time, and confidence band, so that I can review the last week without seeing captured material.
95. As a Guardian, I want Scam and NSFW events clearly distinguished in the authenticated feed, so that a generic notification can remain privacy safe.
96. As a Guardian, I want cleanup and End Supervision to delete alerts, pending uploads where applicable, cooldown state, and Notices according to ownership lifecycle, so that safety data never outlives supervision.

## Implementation Decisions

- Supervision Policy schema v3 adds an `activeScreenSafety` domain with independent `scamText` and `nsfwScreen` sections. Each holds `enabled`, `monitoredPackageNames`, and `sensitivity`. Sensitivity is one of Lower, Standard, or Higher.
- Migration from schema v1 or v2 uses disabled sections, empty package sets, and Standard sensitivity. The backend refuses a v3 policy change until the enrolled Child reports schema-v3 support.
- An enabled section requires at least one unique, valid package from the Child App Catalog. The complete policy remains an immutable, atomic snapshot using the existing desired/applied lifecycle.
- Guardian presents one Active Screen Safety surface with two self-contained detector sections. Their app selections, enabled states, sensitivity, availability, and pending/applied feedback are independent UI concerns but one policy snapshot.
- Scam Text Detection supports Android 8 and later. NSFW Screen Detection supports Android 11 and later because it depends on Accessibility screenshot APIs.
- A dedicated Child-only ML module owns narrow `ScamTextClassifier` and `NsfwImageClassifier` boundaries, one process-wide ONNX environment, model session lifecycle, model-specific preprocessing, and sensitivity-to-model mapping.
- Copy the owner-trained Enfold eight-class quantized BERT ONNX model and its tokenizer. Copy Revive's quantized Marqo NSFW model only; do not include Revive's larger non-quantized duplicate.
- Enabled detector sessions initialize and run a bounded self-check before policy acknowledgement. Failure of any required detector rejects the complete candidate. Sessions remain loaded while enabled and are released after disablement or End Supervision; the shared environment outlives individual sessions.
- Standard scam sensitivity preserves Enfold's result rule: a top class from fraud classes 2 through 7 is positive, while legitimate and non-financial-spam are negative, with no extra probability floor. Standard NSFW sensitivity preserves Revive sensitivity 60, a 0.40 positive threshold.
- Lower and Higher mappings are versioned app-owned tuning constants that require golden-data validation. Guardian never edits a numeric threshold.
- Extend the existing Cereveil Accessibility service as the single orchestrator. It delegates policy checks, candidate extraction, inference, incident deduplication, intervention rendering, and alert queuing behind testable boundaries.
- Scam candidates are individual visible non-editable nodes in the currently visible monitored package. Normalize text, require at least 20 characters, debounce two seconds per package, and preserve the tokenizer's 128-token truncation. Do not concatenate screens or infer sender direction.
- Scam scanning excludes editable and password nodes, Cereveil-owned UI, and deterministic chrome/timestamp candidates. It does not use OCR, notifications, background app data, or message history.
- The scam warning is generic, dismissible through “I understand,” and automatically disappears after 15 seconds. It exposes no input, subtype, probability, or raw confidence.
- NSFW scanning copies Revive's proven adaptive runtime defaults as app-owned constants, including the 200 ms active-touch cadence, two-second active window, 1,000 ms idle cadence, single in-flight capture, relevant-node cache, scroll behavior, watchdog, and blur-hold behavior.
- Screenshot and crop ownership must be established from the Accessibility window belonging to the monitored package. Never include another split-screen app, System UI, system bars, keyboard, or Cereveil overlay.
- Prefer usable image-node crops. If none exists, classify only the monitored app's window and, after a positive result, cover only that app window. Picture-in-picture and split-screen remain best effort within the same ownership rule.
- NSFW positives render only a non-dismissible blur. They produce no warning text, toast, banner, false-positive control, or snooze. Blur is removed when the content/window is gone or a safe recheck replaces the positive state.
- NSFW capture stops while the screen is off or locked, when the section is disabled, or when no relevant monitored window is visible. Both detectors idle when no package relevant to their own selection is visible.
- A Safety Incident is a Child-domain runtime event that owns one Safety Intervention and one Safety Alert. `Safety Intervention` is the umbrella term; `Safety Warning` refers only to scam UI and `NSFW Blur` only to NSFW UI.
- Use a ten-minute, memory-only fingerprint per visible item and package. Scam fingerprints derive from normalized candidate text; NSFW fingerprints use a perceptual representation of the classified crop. Fingerprints are detector-specific, never uploaded, and cleared on restart, section disablement, and End Supervision.
- A Safety Alert uses a random incident identifier and contains only detection type, package, app label, confidence band, policy version, occurrence/creation/expiry timestamps, and backend-derived ownership. It never contains captured content, raw scores, subtype, or fingerprint.
- Child uploads authenticate through the established Child actor boundary. Convex validates active enrollment plus the relevant applied policy/package and deduplicates by active enrollment and incident identifier.
- Child may persist metadata-only Pending Safety Alerts for offline retry for at most seven days. It stores no raw input or fingerprint and uses the same incident identifier for every retry.
- Convex retains individual Safety Alerts for one week, performs indexed bounded cleanup, and deletes them on End Supervision. There is no summary record, summary job, summary notification, or summary UI.
- An immediate Notice is eligible only when the backend receives the alert within five minutes of its occurrence. Stale alerts remain in the feed without producing a Notice.
- The notification cooldown is backend authoritative, fixed and non-sliding, defaults to two minutes, and is keyed by Child Profile plus detection type. Novel alerts within it remain stored. They neither create a Notice nor extend the window, and expiry creates no delayed Notice.
- For an eligible alert outside cooldown, Convex atomically records the alert outcome, creates one Guardian Notice, and advances that type's cooldown. Delivery then sends a generic high-priority FCM wake-up to every active Guardian Device.
- The Child never addresses or sends FCM. FCM contains no Child, detector, app, confidence, or incident details. Guardian authenticates, reconciles authoritative Notices and alerts, deduplicates delivery, and acknowledges Notice state using the existing infrastructure.
- The Guardian system notification may include the locally resolved Child display name but only generic safety wording. Full type, app, occurrence time, and confidence band appear only inside the authenticated newest-first one-week safety feed.
- Active Screen Safety remains disabled in any Play-distributed release until the separate monitoring-compliance work is complete. Development builds may exercise it; this phase does not implement disclosure, consent, persistent notification, listing, permission-declaration, or Data Safety changes.

## Testing Decisions

- Use the highest end-to-end domain seam as the primary contract: a Guardian v3 policy change is accepted, the Child initializes and applies it, a synthetic detector result becomes one idempotent metadata-only Safety Alert, Convex applies cooldown rules, and Guardian Mode reconciles the resulting Notice and feed item.
- Extend Convex integration tests to cover actor authorization, active-enrollment ownership, applied-policy/package verification, schema-v1/v2 defaults, schema-v3 compatibility refusal, atomic candidate rejection, idempotent incident upload, one-week expiry, bounded cleanup, and End Supervision deletion.
- Test the two-minute cooldown with two Children and both detection types. Prove scam and NSFW are independent, Children are independent, concurrent uploads create at most one Notice in a window, suppressed incidents do not slide the window, and expiry does not emit delayed Notices.
- Test timeliness with fresh, exactly-boundary, and stale offline uploads. Prove stale alerts are stored but create no Notice, and retrying the same incident never creates another alert or Notice.
- Test multi-device delivery by proving one approved Notice fans out as generic FCM to every active Guardian Device, inactive devices are excluded, and payloads contain no Child, detector, package, confidence, or content fields.
- Extend Guardian Notice reconciliation tests for missed, duplicated, and out-of-order wake-ups, authenticated feed navigation, read acknowledgement, and cooldown-suppressed alerts that exist without Notices.
- Add pure Android policy/runtime tests for detector availability by Android version, independent package selections, enabled-section validation, v3 migration, full-policy activation, prior-snapshot retention after model failure, and session acquisition/release.
- Add pure scam candidate tests for visible/non-editable scoping, password/editable/overlay/chrome/timestamp exclusion, normalization, 20-character minimum, per-package debounce, individual-node behavior, and 128-token truncation.
- Add pure incident tests for scam and perceptual NSFW fingerprint stability, package separation, detector separation, ten-minute expiry, restart/disable clearing, intervention behavior during cooldown, and absence of raw inputs from queued/uploaded metadata.
- Add NSFW window-scoping tests for ordinary windows, split-screen, picture-in-picture, system bars, keyboards, Cereveil overlays, image-node crops, monitored-window fallback, and stopping on lock, screen-off, disablement, and window departure.
- Add model fixture tests that initialize the exact packaged ONNX assets and tokenizer, run known benign and positive golden inputs, verify Enfold class grouping, verify Standard mappings, and pin versioned Lower/Higher mapping behavior.
- Verify on real Android 11-or-later hardware because Accessibility screenshots, app window ownership, overlays, and ONNX runtime behavior cannot be established by local unit tests alone. Exercise at least Google Messages or WhatsApp for scam, and Instagram, Chrome, plus a video/social surface for NSFW.
- Real-device acceptance covers scrolling, fast content replacement, split-screen, picture-in-picture, monitored-window fallback, lock/screen-off transitions, process restart, offline alert queuing and reconnect, and receipt/reconciliation on two Guardian Devices.
- Verify Android 8 through 10 behavior separately: scam scanning and warning work, while NSFW settings are unavailable and no screenshot work is attempted.
- Add negative privacy assertions at serialization and notification boundaries so raw text, pixels, screenshots, model input, fingerprints, fraud subtype, and raw probability cannot enter Safety Alerts, logs, FCM, Guardian Notices, or the feed.
- Do not make Google Play compliance an acceptance criterion for this development-only phase. A release-enablement task must separately validate every requirement captured by the monitoring compliance ADR before distribution.

## Out of Scope

- Google Play child-monitoring declaration, prominent disclosure and consent, persistent monitoring notification, Play listing language, permissions declaration, and Data Safety implementation.
- Play-track or production release enablement of Active Screen Safety.
- Safety Incident Summaries of any cadence, summary jobs, summary notifications, aggregate trends, or long-term history.
- Uploading or retaining message text, screenshots, image crops, OCR, tokenizer input, model input, fingerprints, raw probabilities, or fraud subcategories.
- Guardian review of raw detected content, manual alert retention, export, search, sharing, deletion, false-positive adjudication, or model-training feedback.
- OCR, notification-content inspection, background app databases, conversation reconstruction, sender-direction inference, and cross-message concatenation.
- Automatic monitored-app selection, hard-coded detector allowlists, per-app sensitivity, and Guardian-editable scan cadence or numeric thresholds.
- A Child bypass, dismiss, false-positive, or snooze action for NSFW blur.
- Remote inference, cloud content scanning, federated learning, model retraining, or automatic model download/update infrastructure.
- Support for NSFW screenshot detection below Android 11.

## Further Notes

- The canonical scam model for v1 is the owner's deployed Enfold eight-class quantized BERT model, not the separate three-class training notebook discovered elsewhere in the workspace.
- Revive and EnfoldAIExpansion are reference implementations and asset sources. Cereveil should copy proven preprocessing and runtime constants while adapting service orchestration, app selection, policy truth, privacy, backend storage, and UI to Cereveil's established architecture.
- The approximate Child-only asset cost is 81 MB: about 61 MB for the quantized scam model, 15 MB for its tokenizer, and 6.4 MB for the quantized NSFW model. Build/package verification should guard against accidentally including Revive's approximately 44 MB non-quantized model.
- Cooldown and fingerprints solve different problems and both are required. Fingerprints suppress repeated incidents for the same visible item before upload; the backend cooldown suppresses Guardian Notices for distinct incidents while preserving their local interventions and alert records.
- App-owned runtime constants remain configurable in code for tuning and testing, but are not Guardian settings. The Guardian-configurable surface is limited to enabled state, explicit packages, and the three sensitivity levels for each detector.
- FCM is part of this phase, but only as the existing generic wake transport from Convex to Guardian Devices. Convex remains the authority for alert existence, cooldown, Notice creation, recipients, and reconciliation.

