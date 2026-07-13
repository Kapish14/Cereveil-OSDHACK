Status: ready-for-agent

# Refresh the Child's location once through FCM

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 79–90.

## What to build

Add the Guardian's one-time Refresh location action without creating a Live Location Session. Convex creates one short-lived, deduplicated request only when desired and applied Location Sharing are enabled and the latest Child health says the device is Online with location and notification capabilities. A high-priority generic command wakes Child Mode, which immediately shows a visible notification, performs one bounded high-accuracy measurement, and submits success or a safe failure.

A request lasts at most 60 seconds and can be created at most once every two minutes per Child Profile. Only a measurement captured at or after backend request time completes it. A poor but fresh result is valid and displays its real accuracy; failure leaves the previous location untouched.

## Acceptance criteria

- [ ] The Guardian action is authorized and creates or returns one pending request only when desired and applied Location Sharing are enabled.
- [ ] Creation rejects with an honest state when the Child Device is Offline or its latest health reports location or notification capability unavailable.
- [ ] One pending request is shared across Guardian Devices, expires after 60 seconds, and backend-owned throttling permits no more than one new request per Child Profile every two minutes.
- [ ] Creation emits a typed `refresh_location` command and high-priority generic FCM wake-up containing no coordinates, policy state, capability state, or Child identity.
- [ ] Child Mode immediately displays a user-visible notification before beginning one bounded high-accuracy measurement.
- [ ] The authenticated result operation accepts success or allowlisted failure and completes the request only with a measurement captured at or after backend `requestedAt`.
- [ ] A fresh low-accuracy result replaces latest state and exposes its actual accuracy; a cached, stale, future-invalid, failed, or timed-out result cannot masquerade as completion.
- [ ] Failure or expiry preserves the previous marker/card and its true captured age, then Child Mode returns to low-power behavior.
- [ ] Command acknowledgement occurs only after the type-specific result is durably accepted; duplicate/missed FCM converges through command reconciliation.
- [ ] Guardian UI shows pending, timeout, throttle, Offline, capability failure, success, poor accuracy, and retry states without implying a continuous session.
- [ ] Backend, scheduler, FCM gateway, Android notification/measurement coordinator, Guardian UI, concurrency, stale/future timestamp, policy-disable, and privacy tests cover the complete flow.

## Blocked by

- [07 — Publish and display latest-only Location State](07-publish-and-display-latest-only-location-state.md)
