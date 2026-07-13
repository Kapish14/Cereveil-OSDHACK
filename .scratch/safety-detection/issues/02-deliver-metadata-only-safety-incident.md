Status: ready-for-agent

# Deliver a Metadata-Only Safety Incident to the Guardian Feed

## Parent

[Active Screen Safety Detection](../PRD.md)

## What to build

Establish the narrow end-to-end reporting path independently of either production classifier. A deterministic Child-side test seam emits a synthetic novel Safety Incident under an applied schema-v3 policy. Child Mode creates one metadata-only Safety Alert, uploads it through its authenticated actor boundary, and Convex verifies enrollment and applied monitoring scope before idempotently storing it. Authenticated Guardian Mode displays the resulting alert in a newest-first, per-Child one-week feed.

The record and every transport boundary must make sensitive input structurally unavailable. This slice delivers authoritative alert storage and feed reconciliation but not offline queuing, FCM notification, or cooldown behavior, which are separate extensions.

## Acceptance criteria

- [ ] One synthetic novel Safety Incident produces one local Safety Intervention event and one metadata-only Safety Alert upload through the same interface production detectors will use.
- [ ] Every alert has a random incident identifier, detection type, package, resolved app label, confidence band, applied policy version, occurrence time, creation time, and expiry time.
- [ ] Ownership, active enrollment, and Child Device identity are derived from authentication rather than trusted from caller-supplied identifiers.
- [ ] Convex verifies that the reported detector is enabled in the applied policy and that the reported package is monitored by that detector.
- [ ] Repeating an upload with the same active enrollment and incident identifier returns the existing outcome and creates no duplicate alert.
- [ ] Unauthorized Guardians, unrelated Child actors, inactive enrollments, unapplied policies, disabled detectors, and unmonitored packages cannot create or read alerts.
- [ ] Guardian Mode shows a selected Child's alerts newest first with only detection type, app, occurrence time, and confidence band.
- [ ] Scam and NSFW alerts are visually distinguishable without revealing raw detected content.
- [ ] Guardian Mode exposes no alert search, export, sharing, manual retention, manual deletion, raw-content detail, fraud subtype, or summary surface.
- [ ] Alert schemas, API payloads, logs, Guardian models, and UI state cannot contain raw text, pixels, screenshots, crops, OCR, tokenizer/model input, fingerprints, fraud subtype, or raw probability.
- [ ] Alerts use a one-week expiry and are included in the established ownership deletion path for End Supervision.
- [ ] Automated tests exercise the complete synthetic Child-to-Guardian path, authorization, scope validation, idempotency, ordering, privacy-negative serialization, and no-summary behavior.

## Blocked by

- [01 – Apply Active Screen Safety Policy v3](01-apply-active-screen-safety-policy-v3.md)

