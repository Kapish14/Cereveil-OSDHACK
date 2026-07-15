# Evaluation

## Honest evaluation status

We did not have enough time to test the two final, quantized models as thoroughly as they should be tested for a safety product. In particular, we did not complete a controlled evaluation of the exact APK artifacts across a large independent application-screenshot/message test set, multiple Android device tiers, or enough real apps and OEMs. We therefore do not claim a final Cereveil accuracy, precision, recall, F1, false-positive rate, or false-negative rate.

The work below is what was actually evaluated during model development and Android integration. Where numeric output was not retained, this document describes the method and observations rather than inventing a score.

## Scam classifier

### What was evaluated

The IndicBERT fraud classifier was developed and exercised separately before being blended into Cereveil's accessibility pipeline. The retained training design uses 30,000 Indian digital-payment messages in English, Hindi, and Hinglish across eight classes:

1. legitimate;
2. non-financial spam;
3. banking phishing;
4. UPI payment fraud;
5. OTP credential theft;
6. KYC/identity scam;
7. investment scam;
8. loan extortion.

The data design includes 10,000 legitimate examples spanning bank debits/credits, mandates, ordinary OTP notices, telecom promotions, government advisories, app notifications, casual chat, and encrypted-looking banking payloads. Eighty-four real messages that had produced false positives in earlier development were added as hard negatives.

The training notebook defines a 24,000-record training split with separate validation and test inputs. It selects the best checkpoint using macro-F1 and includes routines for accuracy, macro-F1, weighted-F1, per-class precision/recall/F1, and a confusion matrix. It also contains a focused 26-message regression list: 20 legitimate/hard-negative messages and six scam messages covering banking, UPI, OTP, KYC, investment, and loan threats.

During Android integration, the classifier was exercised with live/manual messages and exposed its predicted label, confidence, and inference time in the model test flow. The packaged INT8 model also has a connected-device smoke test confirming that the real asset initializes and returns a finite prediction.

### What we can conclude

The domain fine-tuning gives the generic multilingual IndicBERT model a useful Indian payment-scam label space and improves coverage of Hindi/Hinglish and realistic banking language. The expanded legitimate class specifically targets the serious false-positive problem seen in earlier versions.

However, the executed notebook metrics and the final 26-case result were not retained in this repository, and the exact final INT8 artifact was not re-scored on a preserved independent test set. It would be misleading to attach a numeric accuracy or F1 value to the shipped classifier.

### Decision behavior and false positives

The Android decision is multi-class argmax: labels `2..7` show the warning, label `0` is treated as legitimate, and label `1` is ignored as non-financial spam. The current Lower/Standard/Higher selector does not change that scam decision.

False positives remain possible because legitimate bank alerts often contain the same urgency, OTP, KYC, loan, payment, link, and warning vocabulary used by scams. The model can also miss image-only messages, inaccessible custom views, very short text, new scam styles, obfuscated URLs, heavy spelling changes, unfamiliar transliteration, and languages outside the training distribution.

## NSFW classifier

### What was evaluated

The image detector began with the open-source [`Marqo/nsfw-image-detection-384`](https://huggingface.co/Marqo/nsfw-image-detection-384) model. Its upstream model card reports **98.56% accuracy** on Marqo's proprietary 20,000-image test split (10,000 NSFW and 10,000 SFW) and includes comparisons with other open-source detectors plus precision/recall curves at different thresholds.

That upstream number is useful baseline evidence for the original model, but it is not a Cereveil result. Cereveil uses a dynamically INT8-quantized ONNX artifact and classifies crops taken from Android application screens, which is a different runtime and input distribution.

The model was also exercised in an Android real-time screenshot prototype before integration. That work verified model loading, 384×384 preprocessing, local prediction, threshold changes, repeated screenshot scanning, image-region overlays, scroll tracking, and average/maximum inference-time telemetry. Manual testing showed that the system could identify and blur positive image regions, but also showed that false positives and overlay alignment required more threshold tuning and testing across apps. The final Cereveil connected-device smoke test verifies initialization and a finite output, not accuracy.

### Threshold evaluation

The Android integration preserves three operating thresholds:

| Sensitivity | NSFW threshold | Observed/expected trade-off |
|---|---:|---|
| Lower | 0.60 | Fewer false positives, but more harmful content may be missed |
| Standard | 0.40 | Default balance used by the integration |
| Higher | 0.10 | Detects more borderline content, with substantially more false positives |

Unit tests verify this ordering and the `0.40` Standard threshold. We did not complete a post-quantization precision/recall study for these three thresholds.

## Real-time intervention evaluation

### Scam overlay

The integrated flow was checked end to end: accessibility content change, monitored-package filtering, two-second debounce, text extraction, local inference, generic warning overlay, manual dismissal/15-second timeout, and metadata-only reporting. The overlay intentionally avoids repeating the private message.

This verifies system wiring, not model quality. A false warning can interrupt the Child even when the text is legitimate. The current implementation also includes `EditText` nodes when collecting visible text, so a compose/draft field may be classified; this conflicts with ADR-0010's intended exclusion of editable drafts.

### NSFW blur

The integrated blur was checked with screenshot crops and moving image nodes. It creates a blurred copy of each detected region, places it at the accessibility bounds, refreshes those bounds while scrolling, merges new positive regions, removes stale/off-screen overlays, and offers `Hide for 3s` for a false positive.

The blur is not 100% perfect. It is asynchronous and depends on Android's accessibility tree, screenshot permission, current node bounds, model latency, and the foreground app's rendering strategy. Fast scrolls and animations can make it lag; bounds can be too large, too small, or stale; safe images can be blurred; and video, canvas, custom-rendered, secure, or node-less content can escape region detection. The current controller does not implement ADR-0011's intended monitored-window fallback when usable image nodes are unavailable.

## Known failure cases

### Scam text

- Legitimate urgent banking, OTP, KYC, loan, and payment messages can trigger false positives.
- Image-only messages, inaccessible custom views, unopened notifications, and background content are invisible.
- Text below the minimum length or repeated inside the debounce/hash window may be skipped.
- Editable drafts may currently enter classification.
- Novel scam tactics, obfuscation, typos, code-mixing, sarcasm, and out-of-domain language may fail.

### NSFW screen

- Artwork, sports, medical imagery, memes, thumbnails, cartoons, and skin-heavy safe content can be blurred.
- Small, partially visible, animated, video, canvas, secure, and custom-rendered regions can be missed.
- The blur can briefly lag, remain after content moves, cover the wrong bounds, or disappear too early.
- Android/OEM accessibility and screenshot behavior varies across devices and apps.
- INT8 quantization may change borderline decisions relative to the upstream model.

### Interpretation

Warnings and blur overlays are assistive signals, not proof that a message is fraudulent or an image is harmful. False positives and false negatives are expected, and Guardians should not treat an alert as evidence of intent or misconduct.
