Status: ready-for-agent

# Retry Offline Alerts and Expire Safety Data

## Parent

[Active Screen Safety Detection](../PRD.md)

## What to build

Make the Safety Alert reporting path resilient to temporary Child disconnection without persisting detected content. When an online upload cannot complete, Child Mode queues only the already-bounded metadata record with its original incident identifier and occurrence time, retries it idempotently after reconnecting, and expires it after seven days. Convex retains individual alerts for one week with bounded cleanup and removes all owned safety records when supervision ends.

Freshness remains explicit: this slice preserves original occurrence time so later notification processing can distinguish a timely event from a stale offline delivery. It does not add summaries or long-term history.

## Acceptance criteria

- [ ] A failed online upload queues only the metadata allowed by the Safety Alert contract and retains the original incident identifier and occurrence time.
- [ ] Pending records contain no text, pixels, screenshot/crop, OCR, tokenizer/model input, fingerprint, fraud subtype, or raw probability.
- [ ] Reconnect retries use the authenticated Child actor and converge on the same backend alert through incident idempotency.
- [ ] Multiple process restarts and repeated network failures cannot duplicate an alert or reset its original occurrence time.
- [ ] Pending alerts expire and are deleted after at most seven days without upload.
- [ ] Successfully uploaded pending alerts are removed from Child operational storage.
- [ ] Convex exposes only non-expired alerts in the Guardian feed and performs indexed, bounded cleanup after one week.
- [ ] Stale uploads remain eligible for feed storage until their expiry but preserve enough timing information for the notification slice to suppress immediate wake-up after five minutes.
- [ ] End Supervision removes pending Child alert work and backend alerts, cleanup state, cooldown state, and associated Guardian Notices according to ownership lifecycle.
- [ ] No daily, weekly, periodic, or incident summary record, job, notification, or UI is introduced.
- [ ] Automated tests cover offline creation, restart, reconnect, repeated retry, idempotency, seven-day boundaries, one-week backend expiry, bounded cleanup, privacy-negative persistence, and End Supervision deletion.
- [ ] Real-device acceptance verifies offline detection still produces the local warning or blur, reconnect uploads metadata once, and the Guardian feed shows the original occurrence time.

## Blocked by

- [02 – Deliver a Metadata-Only Safety Incident to the Guardian Feed](02-deliver-metadata-only-safety-incident.md)

