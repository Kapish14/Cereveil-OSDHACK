Status: ready-for-agent

# Detect Scam Text and Warn the Child

## Parent

[Active Screen Safety Detection](../PRD.md)

## What to build

Replace the scam detector readiness stub with the owner's Enfold eight-class quantized BERT/ONNX model and tokenizer, packaged only in Child builds behind a narrow classifier interface. Extend the existing Cereveil Accessibility orchestration to select individual visible, non-editable text nodes from a monitored foreground app, classify eligible candidates, and turn a novel positive into a generic dismissible Child warning plus the established metadata-only Safety Alert.

Preserve Enfold's Standard classification semantics while adapting its prototype service and overlay to Cereveil policy, lifecycle, privacy, and incident boundaries. A memory-only fingerprint prevents the same normalized text in the same package from becoming another incident for ten minutes.

## Acceptance criteria

- [ ] Child builds package the canonical Enfold eight-class quantized model and matching tokenizer; Guardian builds package neither asset.
- [ ] The model is accessed through the replaceable scam classifier boundary and participates in candidate-policy initialization, bounded self-check, session reuse, disablement, and End Supervision cleanup.
- [ ] Standard sensitivity is positive when one of fraud classes 2 through 7 is top-ranked, treats legitimate and non-financial-spam as negative, and adds no probability floor.
- [ ] Lower and Higher sensitivities map through named, versioned app-owned constants with pinned golden-data tests rather than Guardian-editable numeric thresholds.
- [ ] Only individual visible, non-editable text nodes owned by a currently visible Scam Monitored App are candidates.
- [ ] Editable fields, password fields, Cereveil UI, obvious timestamps, obvious app chrome, hidden nodes, unmonitored packages, and normalized candidates shorter than 20 characters are excluded.
- [ ] Accessibility events are debounced for two seconds per foreground package, and candidates are not concatenated into a screen or conversation.
- [ ] Tokenization matches the deployed model and truncates to the existing 128-token limit.
- [ ] The implementation does not use OCR, notification content, background app databases, conversation history, sender-direction inference, or raw-input logging.
- [ ] A novel positive displays generic guidance not to share passwords, OTPs, or payment details without showing message text, subtype, confidence, or model output.
- [ ] The warning is dismissible through “I understand” and automatically disappears after 15 seconds.
- [ ] The same normalized text and package is suppressed for ten minutes by a memory-only fingerprint; other packages and NSFW incidents remain independent.
- [ ] Fingerprints are never uploaded or persisted and clear on process restart, scam disablement, and End Supervision.
- [ ] A non-suppressed positive creates exactly one metadata-only Safety Alert through the reporting seam, even when Guardian notification cooldown would suppress a remote Notice.
- [ ] Pure tests cover node filtering, normalization, debounce, token truncation, class grouping, sensitivities, fingerprint expiry/separation, warning lifecycle, and privacy boundaries.
- [ ] Real-device acceptance verifies visible scam content and benign content in Google Messages or WhatsApp, scrolling/rebinding behavior, process restart, and monitored versus unmonitored packages on Android 8 or later.

## Blocked by

- [01 – Apply Active Screen Safety Policy v3](01-apply-active-screen-safety-policy-v3.md)
- [02 – Deliver a Metadata-Only Safety Incident to the Guardian Feed](02-deliver-metadata-only-safety-incident.md)

