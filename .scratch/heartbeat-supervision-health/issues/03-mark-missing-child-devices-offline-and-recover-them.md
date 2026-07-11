Status: ready-for-agent

# Mark Missing Child Devices Offline and Recover Them

## Parent

.scratch/heartbeat-supervision-health/PRD.md

## What to build

Complete the authoritative connectivity lifecycle for enrolled Child Devices. Enrollment must schedule a guarded backend check so a missing first heartbeat does not remain pending forever. Every accepted Supervision Heartbeat must schedule the next guarded check for 45 minutes later. A check marks connectivity Offline only when no newer heartbeat has arrived, while retaining the last reported protection state and capability snapshot. A later accepted heartbeat restores Online immediately.

Guardian Mode must show the complete lifecycle on normal Child dashboard cards and the Child detail/status surface. Before the first report it waits for device status; after the initial deadline it shows Offline with protection still pending; after a previously reporting device goes Offline it shows `Last seen`, retains unavailable capabilities, and describes degraded protection as the condition when last checked.

## Acceptance criteria

- [ ] Enrollment schedules an internal Offline check for 45 minutes after enrollment.
- [ ] If no first heartbeat arrives before that check, connectivity changes from pending to Offline while protection remains pending.
- [ ] Every accepted heartbeat marks connectivity Online and schedules a focused internal Offline check for 45 minutes after backend receipt.
- [ ] An Offline check is guarded by the heartbeat or enrollment state it observed and does nothing when a newer heartbeat has arrived.
- [ ] The backend does not require a global cron scan to transition healthy or missing devices.
- [ ] A device with no accepted heartbeat for 45 minutes becomes Offline.
- [ ] The next accepted heartbeat restores Online immediately and establishes a new guarded deadline.
- [ ] Offline state retains the last reported Fully Protected or Protection Degraded state, capability snapshot, and report timestamp.
- [ ] Offline never implies Tamper Alert, intentional interference, unenrollment, or protection degradation by itself.
- [ ] Guardian dashboard cards show compact Offline and last-known protection labels.
- [ ] Guardian detail/status presentation shows `Last seen` relative time while Offline.
- [ ] A device that never reported shows Offline with no device status received and protection pending.
- [ ] An Offline device that was degraded shows that protection was degraded when last checked and lists the last unavailable capabilities.
- [ ] Relative time continues updating locally using backend server-time offset rather than minute-by-minute backend queries.
- [ ] Backend tests use controlled time to cover initial pending-to-Offline, Online-to-Offline, stale scheduled checks, Offline-to-Online recovery, and preservation of last protection state.
- [ ] Guardian tests cover never-reported Offline, previously protected Offline, previously degraded Offline, last-seen formatting, and recovery presentation.
- [ ] This slice creates no Offline Notice, Recovery Notice, Tamper Alert, FCM delivery, or Location Heartbeat behavior.

## Blocked by

- .scratch/heartbeat-supervision-health/issues/01-report-truthful-first-supervision-health.md
- .scratch/heartbeat-supervision-health/issues/02-keep-supervision-sync-running-in-child-mode.md
