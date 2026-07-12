Status: ready-for-agent

# Configure App Blocking and Active Screen Safety through independent policy sections

## Parent

[Supervision Policy Lifecycle Foundation](../PRD.md) — user stories 5–6, 28–31, 51, and 53–57.

## What to build

Add development-only Guardian controls and typed feature-specific operations for the existing App Blocking and Active Screen Safety policy sections. Both sections must use the shared complete-snapshot lifecycle independently: changing either section preserves the other and every previously desired feature setting, while a later version supersedes obsolete policy-reconciliation work and Child Mode acknowledges only the complete latest snapshot.

Actual app selection, Manual or Scheduled Blocks, Block Screen behavior, monitored-app selection, Scam Text Detection, NSFW Screen Detection, and Safety Warnings remain out of scope.

## Acceptance criteria

- [x] Focused feature-specific operations exist for App Blocking and Active Screen Safety and accept only their typed section, concurrency version, and Save-operation identity.
- [x] Each operation builds from the latest desired complete snapshot and cannot overwrite another feature section with stale client data.
- [x] Effective change, no-op, replay, identifier misuse, stale save, compatibility, version insertion, supersession, desired-state transition, command creation, and acknowledgement all use the shared lifecycle.
- [x] Development Guardian controls derive applied values and pending state from the authoritative desired/applied policy query rather than separate local truth.
- [x] Pending state on one section disables that section but permits a different feature section to be changed.
- [x] Multiple pending feature changes converge into the latest complete desired policy, supersede only obsolete policy commands, and become applied together when Child Mode acknowledges the latest version.
- [x] Child Mode validates and accepts the complete policy atomically and never acknowledges only one section of a version.
- [x] Tests demonstrate that App Blocking, Active Screen Safety, Safe Browsing, and Screen Time settings survive updates to one another without lost fields.
- [x] Tests reuse the shared behavioral seams and do not create separate feature-specific versioning implementations.
- [x] The issue is demonstrable through the development policy screen without claiming that the future enforcement engines are complete.

## Blocked by

- [02 — Change Screen Time Summaries through the complete policy lifecycle](02-change-screen-time-summaries-through-the-complete-policy-lifecycle.md)
