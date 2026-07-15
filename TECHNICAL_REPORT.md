# Technical Report

## Build under review

This report covers the current `main` branch, Android version `1.0`, min SDK 26, target/compile SDK 36, JDK 17, Kotlin 2.3.20, and ONNX Runtime Android 1.17.1.

## Local AI models

| Property | Scam text detector | NSFW screen detector |
|---|---|---|
| Base model | `ai4bharat/indic-bert` (ALBERT family, approximately 33M parameters) | `Marqo/nsfw-image-detection-384` |
| Adaptation | Fine-tuned on 30,000 Indian digital-payment messages spanning English, Hindi and Hinglish | Upstream binary NSFW/SFW classifier integrated into an accessibility screenshot/region pipeline |
| Output | 8 classes: legitimate, non-financial spam, and 6 fraud categories | 2 classes: NSFW and SFW |
| Input | Pure Kotlin IndicBERT Unigram tokenization, maximum 128 tokens | 384×384 RGB, planar CHW, normalized to `[-1, 1]` |
| Packaged model | `model_int8.onnx` | `model_int8.onnx` |
| Model file size | 62,309,329 bytes (62.31 MB; 59.42 MiB) | 6,706,970 bytes (6.71 MB; 6.40 MiB) |
| Extra model assets | Tokenizer 14,969,532 bytes; label map 217 bytes | Label map 32 bytes |
| Runtime | ONNX Runtime Android, CPU, 2 intra-op threads | ONNX Runtime Android, CPU, 3 intra-op threads |

The combined packaged Local AI assets are 83,986,080 bytes (83.99 MB, 80.10 MiB). The observed debug Child APK in the local build output is approximately 207.38 MB; debug APK size includes runtime libraries, resources, unstripped/debug code, and packaging overhead and is not a release download-size claim.

## Training and optimization

The retained fraud training notebook uses:

- 30,000 labeled messages across eight balanced/curated domains;
- a 24,000 training split, with separate validation and test JSONL inputs;
- English, Hindi, and Hinglish examples, including legitimate bank messages, telecom promotions, government advisories, app notifications, casual conversation, and hard negatives;
- six epochs, batch size 32, learning rate `2e-5`, weight decay `0.01`, cosine schedule, 10% warm-up, seed 42;
- macro-F1 as the best-checkpoint selection metric;
- a maximum sequence length of 128.

The shipped fraud artifact is INT8 ONNX. The repository does not retain the exact conversion command, so this report does not claim a more specific quantization scheme.

The NSFW model was exported to ONNX and dynamically quantized with signed INT8 weights for `MatMul` and `Gemm` operations, without per-channel quantization or reduced range. Its source ONNX graph is 22.52 MB and the packaged INT8 graph is 6.71 MB, a 70.2% file-size reduction. Preprocessing and decision thresholds were reproduced in Kotlin, while the intervention was adapted to Cereveil's content-aligned blur overlay and metadata-only reporting.

## Compute utilization

- **CPU:** used for tokenization, bitmap preprocessing, and all ONNX inference. Sessions explicitly cap intra-op threads at two (fraud) and three (NSFW).
- **GPU:** not configured. No ONNX GPU execution provider is selected.
- **NPU:** not configured. No NNAPI/QNN execution provider is selected.
- **Network:** not used by inference. Model assets load from the installed APK.

This CPU-only choice favors predictable availability across API 26+ devices and makes the Local AI claim easy to verify. It may increase latency and energy consumption compared with a tuned hardware accelerator.

## Latency and memory

Formal inference latency and peak-memory benchmarks were not run for this submission at the owner's request. Consequently, no p50/p95 latency, cold-start latency, or peak-PSS number is claimed.

Static implementation facts relevant to performance are:

- the tokenizer loads an approximately 15 MB, 200K-entry vocabulary into Kotlin maps;
- the fraud session loads a 62.31 MB ONNX byte array and uses fixed `1×128` integer inputs;
- the NSFW pipeline allocates a 384×384 bitmap-derived tensor of `3 × 384 × 384` FP32 values (about 1.69 MiB for tensor values, excluding bitmap and runtime overhead);
- sessions stay warm across policy toggles and are reused until supervision ends or the process explicitly releases them;
- screen events are debounced/deduplicated and NSFW work is scoped to candidate regions to reduce repeated inference.

These are implementation observations, not substitutes for device profiling.

## Tested device specification

The current local checkout has been installed on and exercised with:

| Property | Value |
|---|---|
| Manufacturer/model | OnePlus CPH2707 |
| Android | Android 16, API 36 |
| SoC | Qualcomm/QTI SM8635 |
| ABI | arm64-v8a |
| Reported physical RAM | 7,482,424 KiB (8 GB class) |
| Physical display | 1272×2800 at reported density 560 |

Existing connected-device tests verify that both production model assets initialize and return finite outputs. They are smoke tests, not performance or quality benchmarks.

## Non-AI runtime

- Convex TypeScript backend for authoritative state, authorization, commands, retention, and realtime subscriptions.
- Clerk Android for Guardian authentication.
- Firebase Cloud Messaging for generic wake-ups.
- WorkManager for Child background reconciliation.
- Android Accessibility Service for local app enforcement, visible text access, screenshot capture, and overlays.
- Android UsageStats/UsageEvents and LocationManager for device-calculated operational signals.
- WebRTC for disclosed live audio, with Convex carrying only expiring signaling state.

## Engineering limitations

- Local AI is debug-only in the current policy runtime.
- No formal power/battery profiling has been retained.
- CPU-only inference may perform differently across SoCs and thermal states.
- Android/OEM accessibility trees and background restrictions vary materially.
- Release builds are not minified, and the release APK is unsigned in repository-local builds.
