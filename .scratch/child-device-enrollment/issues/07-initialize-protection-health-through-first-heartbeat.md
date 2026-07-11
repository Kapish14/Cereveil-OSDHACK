Status: ready-for-agent

# Initialize Protection Health through First Heartbeat

## Parent

.scratch/child-device-enrollment/PRD.md

## What to build

Keep protection capability reporting out of enrollment completion and use the first authenticated Child Device heartbeat to move Pending Supervision Health into the normal Supervision Health state. Guardian Mode should be able to see the Child as actively enrolled while protection health remains pending until the first heartbeat reports capability availability.

## Acceptance criteria

- [ ] Enrollment completion creates Pending Supervision Health for the Active Enrollment.
- [ ] Enrollment completion does not accept or store an initial protection capability snapshot.
- [ ] First authenticated Child Device heartbeat reports required capability availability through the normal heartbeat path.
- [ ] First heartbeat updates Pending Supervision Health into the appropriate normal health state.
- [ ] Guardian-facing state can distinguish Active Enrollment from Pending Supervision Health.
- [ ] Later heartbeat behavior remains compatible with Fully Protected, Protection Degraded, and offline handling.
- [ ] Tests cover pending health creation, no capability snapshot in completion, first heartbeat update, authorization by ChildDeviceActor, and Guardian-facing pending-to-reported state.

## Blocked by

- .scratch/child-device-enrollment/issues/04-complete-enrollment-with-keystore-proof.md
