# Hackathon APKs

This directory contains the ready-to-install Cereveil role builds:

- `Cereveil-Guardian.apk` for the Guardian's Android device
- `Cereveil-Child.apk` for the Child's Android device

Both APKs are debug-signed hackathon builds. They use the development backend configuration embedded at build time and retain the development-only Local AI demo. Do not treat them as Play Store release artifacts.

## Install

Download the appropriate APK directly from the repository on each device, allow installation from that source when Android prompts, and open the app. For the complete supervision flow, install the Guardian APK first and follow the first-run order in the repository's main README.

With ADB:

```bash
adb -s <guardian-serial> install -r Cereveil-Guardian.apk
adb -s <child-serial> install -r Cereveil-Child.apk
```

## Refresh

From the repository root, with `.env.local` configured:

```bash
npm run android:stage-apks
```

The APKs are stored with Git LFS because the Child build bundles its on-device ML models and exceeds GitHub's regular per-file size limit. Install Git LFS before cloning, or run `git lfs pull` in an existing clone before using the APKs.
