Status: ready-for-agent

# Generalize typed Child Device Commands and FCM reconciliation

## Parent

[Enforcement, Latest Location, and Screen Time Integration](../PRD.md) — user stories 133–138.

## What to build

Prefactor the proven policy-command path into a validated discriminated Child Device Command lifecycle that can safely carry `apply_policy_version`, `refresh_location`, `refresh_screen_time`, and `reconcile_access_grants`. Commands contain opaque references to state owned by feature modules; they never embed the feature payload or become authority themselves.

Preserve the existing apply-policy behavior as the end-to-end proof: generic FCM wakes Child Mode, the authenticated command endpoint reconciles missed or duplicate delivery, and the command reaches its type-specific terminal outcome only after authoritative work succeeds or is safely rejected.

## Acceptance criteria

- [ ] The command model is a strongly validated discriminated union containing the four approved command types and only their bounded opaque references.
- [ ] Existing `apply_policy_version` creation, supersession, reconciliation, acknowledgement, rejection, retry, retention, and Guardian observation continue to work without semantic regression.
- [ ] Every Child push remains generic `child_command` wake-up metadata and contains no policy, package, location, usage, grant, or Child-identifying content.
- [ ] The authenticated Child command endpoint returns bounded pages and independently processable typed commands so one unsupported or failed command does not hide unrelated work.
- [ ] Duplicate and missed FCM delivery converge through endpoint reconciliation; neither FCM acceptance nor command fetch marks feature work complete.
- [ ] Type-specific success and safe stable rejection reasons are represented explicitly, idempotently, and without raw exceptions or sensitive payload data.
- [ ] Command priority remains selected by the creating feature while notification transport continues to own FCM delivery and retry behavior.
- [ ] Schema, Convex lifecycle, FCM gateway, Child coordinator, process-recovery, duplicate-delivery, and privacy tests demonstrate the generalized path using `apply_policy_version`.

## Blocked by

None - can start immediately.
