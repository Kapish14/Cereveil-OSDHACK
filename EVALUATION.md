# Evaluation

## Evidence policy

This document separates upstream model-card results, retained Cereveil evaluation methodology, and application smoke tests. A number is not presented as a Cereveil result unless the corresponding output is retained and attributable.

## Scam classifier

### Dataset and method

The retained training notebook defines 30,000 Indian digital-payment messages across eight classes:

1. legitimate;
2. non-financial spam;
3. banking phishing;
4. UPI payment fraud;
5. OTP credential theft;
6. KYC/identity scam;
7. investment scam;
8. loan extortion.

The corpus contains English, Hindi, and Hinglish. Legitimate/hard-negative coverage includes bank debits/credits, mandates, OTP advisories, telecom promotions, government advisories, app notifications, encrypted-looking bank payloads, and casual chat. The notebook expects 24,000 training records and separate validation/test JSONL files. Checkpoint selection uses macro-F1, and the test routine computes accuracy, macro-F1, weighted-F1, a per-class classification report, and a confusion matrix.

The application decision baseline is multi-class argmax: labels `2..7` alert, `0` is safe, and `1` is ignored as non-financial spam. This preserves fraud type information locally while transmitting only a detector type and confidence band.

### Retained result

The notebook's execution outputs and private dataset records are not committed. Therefore this submission makes **no numeric accuracy/F1 claim** for the fine-tuned or quantized fraud model. The notebook remains reproducible when the dataset is attached through `CEREVEIL_DATA_DIR`.

A retained 26-message regression list covers 20 expected-safe/hard-negative messages and six expected-alert scams, but its execution output is also not retained. It documents intended failure-pressure cases, not a pass rate.

### Baseline comparison

The meaningful qualitative baseline is generic IndicBERT versus domain fine-tuning. The base multilingual IndicBERT supplies Indic-language representations but has no Cereveil fraud head. Fine-tuning adds Indian payment/scam classes and Hindi/Hinglish domain examples. No numerical comparison with keyword rules or an unadapted base model is retained.

## NSFW classifier

### Upstream benchmark

The [`Marqo/nsfw-image-detection-384`](https://huggingface.co/Marqo/nsfw-image-detection-384) model card reports **98.56% accuracy** on its proprietary dataset, with a 20,000-image test set (10,000 NSFW and 10,000 SFW), and publishes threshold/precision/recall comparison plots against two other open-source detectors.

This is an **upstream FP32 model-card result**, not a Cereveil score. The underlying dataset is proprietary and is not included here, and the quantized Android artifact has not been re-evaluated against that test set.

### Cereveil adaptation

Cereveil dynamically quantizes signed INT8 weights for `MatMul`/`Gemm`, preprocesses candidate regions at 384×384, and applies policy thresholds:

| Sensitivity | NSFW threshold | Expected trade-off |
|---|---:|---|
| Lower | 0.60 | Fewer false positives, more missed positives |
| Standard | 0.40 | Default balance |
| Higher | 0.10 | More positives, more false positives |

Unit tests verify threshold ordering and standard threshold behavior. A connected-device smoke test verifies that the packaged model initializes and returns a finite confidence value.

## System-level verification

The repository includes unit/integration tests for policy parsing, detector decision rules, incident deduplication, authorization, retention, and backend feature flows. The connected smoke test exercises the actual packaged ONNX assets on Android. These tests establish wiring and invariants; they do not establish real-world detector accuracy.

## Known failure cases

### Current ADR deviations

The current `main` implementation has two known gaps against the intended architecture:

- `EnfoldScamController.collectTextNodes` includes both `TextView` and `EditText` nodes. This conflicts with ADR-0010's requirement to exclude editable drafts and should be fixed before production distribution.
- `ReviveNsfwController` classifies accessibility-discovered image regions but does not implement ADR-0011's required monitored-window fallback when usable nodes are unavailable for video, canvas, or custom rendering.

These are implementation defects/coverage gaps, not accepted product behavior.

### Scam text

- Image-only text, inaccessible custom views, unopened notifications, background messages, and content not exposed by accessibility are invisible.
- The implementation is limited to selected packages and a supported messaging-package allow-list.
- Short text under the minimum node length and events inside the debounce window may be skipped.
- Visible editable fields can enter the collected node set in the current implementation; compose/draft text may therefore be classified when the messaging surface contains an edit box. This is the ADR-0010 deviation identified above.
- Sarcasm, novel scam tactics, obfuscated URLs, transliteration shifts, typos, code-mixing, and out-of-domain languages may fail.
- Legitimate messages with urgency, OTP, KYC, loan, or payment language can resemble scams.
- The current sensitivity selector does not change the scam argmax decision.

### NSFW screen

- Cartoons, artwork, skin-heavy sports/medical content, memes, thumbnails, and cropped bodies can be false positives.
- Small, occluded, animated, video, canvas, custom-rendered, or rapidly moving regions may be missed or briefly misaligned with the blur. Missing window fallback is the ADR-0011 deviation identified above.
- Accessibility screenshot APIs require Android 11+, may be restricted by secure windows, and differ across OEMs/apps.
- Quantization can change decision boundaries near thresholds; post-quantization accuracy has not been measured.
- A very low `Higher` threshold intentionally increases false positives.

### Product behavior

- A warning/blur is an assistive intervention, not proof of intent or misconduct.
- Offline metadata arrives late; the Child intervention occurs locally even if the Guardian never receives an alert.
- CPU-only inference may be delayed under thermal throttling, memory pressure, or heavy foreground load.

## Recommended next evaluation

- Freeze and version a consented, de-identified Hindi/Hinglish/English fraud test set.
- Export retained per-class precision, recall, F1, confusion matrix, and calibration results for both FP32 and INT8 artifacts.
- Build a licensed NSFW/SFW application-screenshot test set and re-evaluate after quantization at all three thresholds.
- Measure end-to-end intervention latency, peak PSS, energy, and false-overlay duration across low-, mid-, and high-tier Android devices.
- Run app/OEM accessibility compatibility tests and record coverage separately from model quality.
