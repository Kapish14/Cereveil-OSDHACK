Status: ready-for-agent

# Deliver deduplicated Tamper Alerts

## Parent

`.scratch/authoritative-messaging-fcm-delivery/PRD.md`

## What to build

Deliver a Tamper Alert through the established Guardian Notice path when an authenticated Supervision Heartbeat first reports one or more required Protection Setup capabilities becoming unavailable. Group capabilities newly unavailable in the same heartbeat into one authoritative alert. Persistent degradation must not repeat alerts, capability recovery must silently resolve the prior degradation, and a later available-to-unavailable transition may alert again. Connectivity and protection transitions remain independent, allowing one heartbeat to create both Recovery Notice and Tamper Alert while never inferring tampering from Offline state.

## Acceptance criteria

- [ ] The first degraded heartbeat creates one Tamper Alert containing the safe authoritative set of all currently unavailable required capabilities.
- [ ] A later heartbeat groups only capabilities newly changing from available to unavailable into one alert.
- [ ] Repeated heartbeats with the same unavailable capabilities do not duplicate the alert.
- [ ] Capability recovery resolves the prior degradation without creating a restoration notice; a later re-degradation can create a new alert.
- [ ] No Offline transition, missing push, or unexplained heartbeat absence creates or labels a Tamper Alert.
- [ ] Recovery and capability-degradation transitions are evaluated independently, and one heartbeat can create both notice types exactly once.
- [ ] Tamper wake-up delivery is high-priority but otherwise uses the same minimal metadata and authoritative per-device reconciliation flow.
- [ ] Integration tests cover first degraded heartbeat, grouped loss, persistent degradation, partial and complete recovery, re-degradation, Offline neutrality, and simultaneous Recovery plus Tamper.

## Blocked by

- `01-deliver-an-offline-notice-end-to-end.md`
