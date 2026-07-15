# Attribution

## Pretrained models

### AI4Bharat IndicBERT

- Source: [`ai4bharat/indic-bert`](https://huggingface.co/ai4bharat/indic-bert)
- Project/paper context: [AI4Bharat IndicBERT](https://github.com/AI4Bharat/indic-bert)
- License note: the linked AI4Bharat project repository carries the MIT License; the hosted model is currently access-restricted, so verify the weight revision and its applicable terms before redistribution.
- Use in Cereveil: base ALBERT-family multilingual representation model, fine-tuned into an eight-class English/Hindi/Hinglish Indian digital-payment fraud classifier and packaged as INT8 ONNX.
- Cereveil's labeled scam-message corpus and fine-tuned classification head are project-specific; pretrained-model licensing/terms still apply.

### Marqo NSFW Image Detection 384

- Source/model card: [`Marqo/nsfw-image-detection-384`](https://huggingface.co/Marqo/nsfw-image-detection-384)
- License: Apache-2.0 as declared by the upstream model card. The packaged artifact should remain attributable to the exact upstream model/revision used for export.
- Use in Cereveil: binary NSFW/SFW image classifier, exported to ONNX, dynamically INT8-quantized, and integrated into a local accessibility screenshot/region blur pipeline.
- The model card describes a proprietary training/evaluation dataset; that dataset is not redistributed by Cereveil.
- Reported 98.56% is the upstream model-card result and is not represented as Cereveil's quantized result.

## Project data

The fraud model was fine-tuned on a project-curated 30,000-message dataset covering English, Hindi, and Hinglish Indian payment and scam scenarios. It includes legitimate and hard-negative categories as well as six fraud families. The dataset is not committed because message-like training material may carry provenance/privacy constraints; only the training notebook and packaged model are included. Any future publication should include a dataset card, consent/provenance statement, license, de-identification process, and split-generation procedure.

## Principal libraries and platforms

| Component | Project | Role |
|---|---|---|
| Local inference | [ONNX Runtime](https://onnxruntime.ai/) | Android CPU execution of INT8 ONNX models |
| Android UI/runtime | [AndroidX](https://developer.android.com/jetpack/androidx), [Jetpack Compose](https://developer.android.com/compose) | UI, lifecycle, testing and background APIs |
| Backend | [Convex](https://www.convex.dev/) | Authorized realtime state, functions, HTTP actions and scheduled cleanup |
| Guardian auth | [Clerk](https://clerk.com/) | Guardian identity and sessions |
| Push | [Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging) | Generic device wake-ups |
| QR generation | [ZXing](https://github.com/zxing/zxing) | Guardian enrollment QR generation |
| QR scanning | [Google Code Scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) | Child enrollment scanning without a custom camera permission |
| Maps | [Google Maps SDK for Android](https://developers.google.com/maps/documentation/android-sdk) | Guardian latest-location view |
| Live audio | [WebRTC Android](https://webrtc.org/) | Peer media and ICE connectivity |
| Testing | [Vitest](https://vitest.dev/), [`convex-test`](https://github.com/get-convex/convex-test), JUnit, AndroidX Test | Backend, JVM and device tests |

See `gradle/libs.versions.toml`, `package.json`, and `package-lock.json` for exact dependency versions and transitive packages.

## Android platform APIs

Cereveil relies on Android Accessibility Service, UsageStats/UsageEvents, LocationManager, Android Keystore, WorkManager, notifications, and foreground-service APIs. Their behavior and user disclosures are governed by Android platform and distribution policies.

## Pre-existing design and implementation inputs

The project builds on the pretrained open-source models above and standard open-source libraries/APIs. Cereveil-specific work includes role-specific product integration, Keystore device identity, Convex authorization/lifecycle logic, policy-controlled monitored-app selection, Hindi/Hinglish fraud fine-tuning, pure Kotlin IndicBERT tokenization, local incident reduction, and the content-aligned blur/reporting behavior.

## Trademarks and service terms

Android, Google Play services, Firebase, Google Maps, Clerk, Convex, ONNX Runtime, WebRTC, Hugging Face, AI4Bharat, and Marqo names belong to their respective owners. Use of hosted services and pretrained artifacts remains subject to their current terms and model licenses; verify those terms before production distribution.
