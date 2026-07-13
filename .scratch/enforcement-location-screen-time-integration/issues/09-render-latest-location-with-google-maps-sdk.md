Status: ready-for-agent

# Render latest location with Google Maps SDK

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 93–97.

## What to build

Enhance the latest-location surface with the standard Google Maps SDK for Android. Render one marker and its accuracy circle from the authoritative Location State while keeping the existing coordinate/status card and `geo:` action available whenever the SDK, key, network, or renderer is unavailable.

Introduce a safe external key-injection contract and setup documentation for separately restricted development and production keys. Do not commit a raw key or enable Places, Routes, Street View, reverse geocoding, advanced map IDs, or other Google Maps Platform services. Actual Google Cloud project/key creation is a human setup step supported by the documented contract, not a prerequisite for compiling or testing the fallback path.

## Acceptance criteria

- [ ] The location surface renders one marker at the latest coordinate and an accuracy circle using the reported radius, without deriving new authoritative location state from the map.
- [ ] Marker/card age and accuracy update from the latest Convex state, and stale or refresh-failure states remain visible independently of map rendering.
- [ ] Missing key, SDK initialization failure, renderer failure, and network failure leave the coordinate/status card and `geo:` fallback usable.
- [ ] Development builds can receive a key from an ignored local source and release builds from external secret configuration; no raw key is committed or logged.
- [ ] Setup documentation covers separate development/production keys restricted by Android package name and the correct signing-certificate fingerprints.
- [ ] Only the standard Maps SDK dependency and APIs required for the marker, camera, and accuracy circle are used; separately scoped services remain excluded.
- [ ] Automated tests cover presentation state and fallback behavior without pixel-testing or depending on live Google services.
- [ ] A documented real-device smoke check verifies a supplied restricted key, marker, accuracy circle, stale state, and fallback action.

## Blocked by

- [07 — Publish and display latest-only Location State](07-publish-and-display-latest-only-location-state.md)
