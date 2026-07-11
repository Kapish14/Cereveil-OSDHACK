Status: ready-for-agent

# Observe Enrollment State from Guardian QR Setup

## Parent

.scratch/child-device-enrollment/PRD.md

## What to build

Wire Guardian Mode QR setup to observe the selected Child Profile's enrollment summary while the QR is visible. When Child Device enrollment completes elsewhere, the Guardian UI should stop showing the QR and transition to an enrolled state that distinguishes Active Enrollment from pending policy application and Pending Supervision Health.

This slice should make the Guardian side react correctly to enrollment state changes; it does not need to implement Child Mode completion itself.

## Acceptance criteria

- [ ] Guardian Mode observes the selected Child Profile's enrollment summary while the QR screen is active.
- [ ] Once an Active Enrollment exists, Guardian Mode auto-transitions away from the QR screen.
- [ ] The Guardian state distinguishes unenrolled, actively enrolled, policy pending/applied, and protection health pending states.
- [ ] A stale QR screen stops presenting the code as usable after enrollment completes.
- [ ] The enrollment summary does not fetch dashboard analytics, location, Safety Alerts, Screen Time Summaries, Access Requests, or full policy details.
- [ ] The UI remains correct if the Guardian app was backgrounded and returns after enrollment completed.
- [ ] Tests cover observing an unenrolled profile, transition after Active Enrollment appears, stale QR handling, and pending policy/protection presentation using fake data sources.

## Blocked by

- .scratch/child-device-enrollment/issues/01-create-and-display-guardian-enrollment-code-qr.md
