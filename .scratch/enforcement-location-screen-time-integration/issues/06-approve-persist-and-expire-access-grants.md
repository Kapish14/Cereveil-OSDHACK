Status: ready-for-agent

# Approve, persist, and expire Access Grants

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 40 and 55–65.

## What to build

Complete Access Request approval by creating an authoritative, bounded Access Grant and reconciling it to Child Mode. The backend starts the grant at approval time. Child Mode persists the absolute expiry in operational storage so delayed delivery, process death, reboot, or Offline operation can neither cancel nor extend access, and it immediately re-evaluates a visible app when the grant expires.

Manual Blocks offer fixed 15, 30, 45, or 60 minute grants. Schedule-only requests cap choices at the remaining continuous coverage and may offer until the block ends. An overlapping schedule does not shorten a grant produced by a Manual Block. Active grants are deliberately non-revocable in v1.

## Acceptance criteria

- [ ] The first valid approval transaction resolves the pending request and creates exactly one grant with server-owned `startsAt` and absolute `expiresAt` values.
- [ ] Guardian approval offers only 15, 30, 45, and 60 minutes for Manual Blocks and communicates that active grants cannot be revoked in v1.
- [ ] Schedule-only choices never outlive remaining continuous scheduled coverage and support an until-this-block-ends choice; overlapping schedules are treated as continuous coverage.
- [ ] A Manual Block grant is governed by its selected duration and is not shortened by an overlapping Scheduled Block boundary.
- [ ] Convex creates a `reconcile_access_grants` command and prompt generic wake-up without embedding package, duration, request, or grant details in FCM.
- [ ] Child Mode fetches authorized active grants, ignores already expired grants, persists absolute expiry, and removes the Block Screen only while the relevant grant is valid.
- [ ] Delayed fetch provides only the remaining interval; process death, reboot, duplicate commands, and Offline operation neither restart nor extend it.
- [ ] Local expiry promptly removes the grant and re-evaluates the visible app without requiring network connectivity, restoring the Block Screen when still blocked.
- [ ] Grant acknowledgement occurs only after durable local reconciliation; rejection and retry use bounded privacy-safe outcomes.
- [ ] Backend, Guardian approval UI, FCM, Child coordinator/store, overlay, concurrency, delayed-delivery, reboot, Offline-expiry, and block-kind tests cover the end-to-end path.

## Blocked by

- [05 — Create, notify, deny, and expire Access Requests](05-create-notify-deny-and-expire-access-requests.md)
