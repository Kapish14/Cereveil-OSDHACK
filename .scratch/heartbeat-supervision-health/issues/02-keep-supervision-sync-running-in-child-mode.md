Status: ready-for-agent

# Keep Supervision Sync Running in Child Mode

## Parent

.scratch/heartbeat-supervision-health/PRD.md

## What to build

Keep an enrolled Child Device reporting after the enrollment screen is gone. Add one unique WorkManager supervision-sync cycle that requests execution approximately every 15 minutes with network connectivity required. Each cycle independently retries a locally pending policy acknowledgement when necessary and submits a Supervision Heartbeat. It must refresh an expired Child Device JWT through the existing Keystore challenge flow, preserve the success of either operation when the other fails, use exponential backoff for transient failures, and stop retrying for a definitively revoked credential or inactive enrollment without clearing Role Lock.

Expose the resulting Online state through the normal Guardian Child dashboard and detail/status experience. Dashboard cards should remain compact, while the detail surface shows a locally updating `Last checked` relative time and unavailable capabilities when protection is degraded.

## Acceptance criteria

- [ ] Child Mode schedules one uniquely named periodic supervision-sync job for the active local enrollment without creating overlapping duplicates.
- [ ] Periodic work requests an approximately 15-minute interval and requires network connectivity.
- [ ] Ordinary process death and device restart rely on WorkManager persistence rather than the enrollment ViewModel remaining alive.
- [ ] The existing immediate first heartbeat remains available in addition to periodic work.
- [ ] Local state can distinguish an applied policy version from the last successfully acknowledged version, or provides equivalent pending-acknowledgement behavior.
- [ ] A cycle skips policy acknowledgement when no locally applied version is pending acknowledgement.
- [ ] A cycle does not fetch the Supervision Policy merely because the periodic heartbeat is due.
- [ ] Policy acknowledgement and heartbeat submission are attempted independently.
- [ ] Successful acknowledgement is retained when heartbeat fails, and successful heartbeat reporting is retained when acknowledgement fails.
- [ ] Expired Child Device JWTs are refreshed through the existing Keystore-backed challenge flow before authenticated sync.
- [ ] Transient network, backend, and recoverable token-refresh failures request exponential WorkManager backoff with an initial delay around 10 minutes and no custom rapid-retry loop.
- [ ] Definitively revoked Child Device Credentials and inactive Active Enrollments stop retries for that enrollment.
- [ ] Terminal sync authorization failure does not clear Role Lock or silently remove local enrollment state.
- [ ] The recurring Supervision Heartbeat reports the current required capability snapshot without GPS coordinates or VPN.
- [ ] Guardian dashboard cards show compact Online and protection labels for enrolled Children.
- [ ] Guardian detail/status presentation shows `Last checked` relative time and unavailable capabilities when degraded.
- [ ] Guardian relative time uses backend `serverNow` to establish clock offset and advances locally without querying Convex every minute.
- [ ] Child JVM tests cover unique scheduling through a fakeable seam, pending-acknowledgement behavior, partial success, JWT refresh, transient retry, terminal failure, and capability payload.
- [ ] Guardian tests cover Online card/detail presentation, last-checked formatting, and unavailable capability labels.

## Blocked by

- .scratch/heartbeat-supervision-health/issues/01-report-truthful-first-supervision-health.md

