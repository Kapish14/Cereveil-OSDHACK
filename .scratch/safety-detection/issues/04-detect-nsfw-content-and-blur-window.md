Status: ready-for-agent

# Detect NSFW Content and Blur a Normal App Window

## Parent

[Active Screen Safety Detection](../PRD.md)

## What to build

Replace the NSFW detector readiness stub with Revive's quantized Marqo/ONNX classifier, packaged only in Child builds behind a narrow image-classifier interface. For the ordinary single-app, full-screen case, extend Cereveil's existing Accessibility orchestration to track usable image nodes, capture and classify their monitored-app crops at Revive's adaptive cadence, and obscure a novel positive with a non-dismissible blur. The same incident also follows the metadata-only Safety Alert path.

This slice establishes the working baseline on Android 11 or later. Split-screen, picture-in-picture, monitored-window fallback, and the complete lock/window lifecycle hardening belong to the following issue.

## Acceptance criteria

- [ ] Child builds package Revive's quantized NSFW model behind the replaceable classifier boundary; Guardian builds exclude it and the larger non-quantized Revive model is not packaged anywhere.
- [ ] Scam and NSFW sessions share one process-wide ONNX environment without individual classifiers closing shared runtime ownership.
- [ ] The model participates in candidate-policy initialization, bounded self-check, session reuse, disablement, and End Supervision cleanup.
- [ ] Standard sensitivity preserves Revive sensitivity 60, equivalent to a 0.40 positive-confidence threshold.
- [ ] Lower and Higher sensitivities use named, versioned app-owned mappings with pinned golden-data tests.
- [ ] On Android 11 or later, only usable image-node crops belonging to a visible NSFW Monitored App are classified in the baseline full-screen case.
- [ ] The default active-touch cadence is 200 milliseconds for a two-second activity window, idle cadence is 1,000 milliseconds, and only one screenshot capture may be in flight.
- [ ] A positive produces blur only: no warning, dialog, banner, toast, message, false-positive action, snooze, or dismiss control appears.
- [ ] Blur prevents the positive region from remaining visibly usable and is removed after the relevant content is replaced or a safe recheck succeeds.
- [ ] A perceptual crop-plus-package fingerprint suppresses visually equivalent repeats for ten minutes while remaining independent from scam fingerprints and other packages.
- [ ] The fingerprint remains memory-only, is never uploaded, and clears on process restart, NSFW disablement, and End Supervision.
- [ ] A non-suppressed positive creates exactly one metadata-only Safety Alert through the established reporting seam, regardless of remote notification cooldown.
- [ ] No screenshot, crop, pixel data, model input, fingerprint, or raw probability enters logs, persistence, backend payloads, Guardian state, or FCM state.
- [ ] Automated tests pin packaged model initialization, benign and positive golden fixtures, sensitivity mapping, capture throttling, single-flight behavior, blur lifecycle, fingerprints, and metadata-only reporting.
- [ ] Real-device acceptance verifies positive and benign image-node content in at least Instagram or Chrome on Android 11 or later.

## Blocked by

- [01 – Apply Active Screen Safety Policy v3](01-apply-active-screen-safety-policy-v3.md)
- [02 – Deliver a Metadata-Only Safety Incident to the Guardian Feed](02-deliver-metadata-only-safety-incident.md)

