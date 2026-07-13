Status: ready-for-agent

# Publish and display latest-only Location State

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 69–78, 91–92, and 100–101.

## What to build

When the applied Location Sharing policy is enabled, have Child Mode produce privacy-bounded Location Heartbeats and let Guardian Mode observe one authoritative latest measurement. Low-power work runs approximately every 15 minutes and after significant movement of roughly 250 metres, with movement uploads rate-limited to one per five minutes. Offline heartbeats are discarded; reconnection obtains a new measurement instead of replaying a route.

Convex stores only latitude, longitude, accuracy, and captured time for the newest accepted measurement. Guardian Mode presents coordinates, accuracy, true measurement age, connectivity, capability, and a stale warning independently. This slice includes the provider-neutral status card and `geo:` action, so location remains useful before Google Maps is configured.

## Acceptance criteria

- [ ] Location work starts only after the Location Sharing section is applied and stops locally when it is disabled.
- [ ] Low-power measurement is attempted approximately every 15 minutes and after roughly 250 metres of significant movement, with movement-triggered uploads capped at one per five minutes.
- [ ] No Location Heartbeat is queued while Offline; reconnection requests a current measurement rather than uploading missed points.
- [ ] The authenticated Child route accepts bounded latitude, longitude, accuracy, and captured time and derives ownership through the complete active actor chain.
- [ ] Convex maintains one latest Location State per active enrollment and accepts only a measurement newer than the stored captured time; receipt time never substitutes for measurement time.
- [ ] Location Sharing disablement immediately removes backend latest/pending state and instructs Child Mode to cancel and clear unsent local work.
- [ ] Guardian Mode shows coordinates, accuracy radius, actual age, connectivity, capability, stale state, loading, no-data, and failure independently; data older than 30 minutes says “Location may be outdated.”
- [ ] A provider-neutral `geo:` action can open the latest coordinate in a compatible installed map app.
- [ ] Location capability requires fine/background permission and the system location service; poor measurement conditions are shown as measurement quality/failure rather than tampering.
- [ ] Location failures do not modify Supervision Heartbeat liveness, and no point, route, session, or history table is introduced.
- [ ] Backend, Android scheduler/coordinator, Guardian repository/ViewModel/Compose, disablement, ordering, Offline/reconnect, stale-state, capability, authorization, and privacy tests cover the complete latest-only path.

## Blocked by

- [02 — Apply Supervision Policy v2 and Trusted Device Time](02-apply-supervision-policy-v2-and-trusted-device-time.md)
- [03 — Generalize typed Child Device Commands and FCM reconciliation](03-generalize-typed-child-device-commands-and-fcm-reconciliation.md)
