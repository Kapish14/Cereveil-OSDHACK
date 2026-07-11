Status: ready-for-agent

# Report Truthful First Supervision Health

## Parent

.scratch/heartbeat-supervision-health/PRD.md

## What to build

Deliver a truthful first Supervision Health report from an enrolled Child Device through Guardian Mode. Correct the initial policy flow so the local policy runtime starts before Child Mode acknowledges application, and keep policy application independent from heartbeat delivery. Replace the single combined health status with independent connectivity and protection states, remove VPN from the hackathon capability contract, and use the existing custom Child Device JWT HTTP boundary to accept the authenticated first Supervision Heartbeat. Guardian Mode must distinguish an Active Enrollment that is waiting for its first report from one whose first capability snapshot has arrived, without claiming that heartbeat failure means policy application failed.

Introduce only the focused shared Child HTTP request handling needed to centralize JWT verification, ChildDeviceActor resolution, and safe authentication failures for the policy acknowledgement and heartbeat paths. Do not expand this slice into the complete planned request-context, logging, or native Convex custom-auth architecture.

## Acceptance criteria

- [ ] Supervision Health stores connectivity independently from protection state.
- [ ] Enrollment creates pending connectivity and Pending Supervision Health without a capability snapshot.
- [ ] The hackathon heartbeat capability contract contains Accessibility service, Usage Access, location permission, microphone permission, notification permission, and battery-optimization exemption.
- [ ] VPN is removed from the current Android capability model, heartbeat payload, backend validation and storage, protection calculation, Guardian display mapping, and tests.
- [ ] Future Safe Browsing and VPN domain and architecture documentation remains intact.
- [ ] Child Mode stores the fetched policy and successfully starts the local policy runtime before acknowledging that version to Convex.
- [ ] Policy application state is not derived from heartbeat delivery success.
- [ ] Policy acknowledgement failure and heartbeat failure produce independent state outcomes.
- [ ] The first authenticated Supervision Heartbeat derives liveness from backend receipt and uses Convex server time for `lastHeartbeatAt`.
- [ ] The heartbeat does not accept Online, Offline, a client-owned heartbeat timestamp, GPS coordinates, policy contents, or an FCM token.
- [ ] Every required capability true produces Fully Protected; any required capability false produces Protection Degraded and retains the unavailable capability identifiers.
- [ ] Guardian Mode can distinguish waiting for first device status from a received Fully Protected or Protection Degraded report.
- [ ] Guardian Mode can show policy application independently from protection reporting.
- [ ] Child policy acknowledgement and heartbeat HTTP paths share focused authentication, actor-resolution, and safe-failure behavior where practical.
- [ ] Backend tests cover pending creation, authenticated first report, server-owned timestamps, capability derivation, malformed payloads, unauthorized actors, and VPN exclusion.
- [ ] Child tests cover runtime-before-acknowledgement ordering and independent policy/heartbeat results.
- [ ] Guardian tests cover pending and first-reported protection presentation without conflating policy state.

## Blocked by

None - can start immediately

