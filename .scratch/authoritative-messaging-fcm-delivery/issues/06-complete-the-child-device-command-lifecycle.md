Status: ready-for-agent

# Complete the Child Device Command lifecycle

## Parent

`.scratch/authoritative-messaging-fcm-delivery/PRD.md`

## What to build

Complete the independently processed Child Device Command lifecycle around `apply_policy_version`. Support explicit acknowledgement, safe rejection, supersession, cancellation, and expiry without introducing a global command queue. Repeated intent creation must be idempotent, and a newer desired policy version must supersede only older pending policy-reconciliation intent for the same target. Unrelated future command families must remain unaffected by that replacement rule.

## Acceptance criteria

- [ ] Command lifecycle explicitly supports pending, acknowledged, rejected, superseded, cancelled, and expired terminal outcomes with server-owned timestamps.
- [ ] Command creation is idempotent by stable server-owned intent identity and repeated creation cannot duplicate effective work.
- [ ] A newer desired Supervision Policy version supersedes an older pending `apply_policy_version` command for the same target.
- [ ] Policy version is not treated as a global Child Device Command sequence, and supersession is scoped to the replaceable policy-reconciliation intent.
- [ ] Stale or superseded acknowledgement cannot overwrite a newer desired or applied Policy Application State.
- [ ] One pending, rejected, or failed command does not block fetching or processing other commands.
- [ ] Child Mode can reject permanently unexecutable work using a small stable safe-reason enum without sending raw exception text.
- [ ] Pending `apply_policy_version` commands expire after seven days and cannot be applied or acknowledged as current after expiry.
- [ ] Cancellation and every terminal transition are idempotent and authorized to the targeted active Child Device lifecycle.
- [ ] Integration and Child coordinator tests cover every lifecycle transition, duplicate intent, policy supersession, stale acknowledgement, independent processing, and safe rejection.

## Blocked by

- `05-reconcile-apply-policy-version-commands-end-to-end.md`
