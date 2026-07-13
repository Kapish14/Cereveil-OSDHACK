Status: ready-for-agent

# Apply Active Screen Safety Policy v3

## Parent

[Active Screen Safety Detection](../PRD.md)

## What to build

Deliver the complete Guardian-to-Child policy path for Active Screen Safety. Guardian Mode presents one settings surface with independent Scam Text Detection and NSFW Screen Detection sections. Each section owns its enabled state, explicit monitored-app selection from the latest App Catalog, and Lower, Standard, or Higher sensitivity. Saving creates a complete schema-v3 policy and uses the existing desired-versus-applied lifecycle rather than treating the backend write as successful application.

Child Mode advertises schema-v3 support, validates platform availability and section invariants, asks replaceable detector-readiness boundaries to initialize and self-check every enabled detector, and atomically accepts or rejects the complete candidate. A rejection preserves the previously applied snapshot. This slice establishes detector lifecycle seams but may use deterministic test implementations; the production models arrive in the detector slices.

## Acceptance criteria

- [ ] Guardian Mode shows separate Scam Text Detection and NSFW Screen Detection sections with independent enabled states, monitored packages, and sensitivities.
- [ ] Each section supports searchable multi-selection from the latest Child App Catalog, allows the same package in both sections, and never checks suggestions or newly installed apps automatically.
- [ ] Selected packages and sensitivity survive section disablement and temporary uninstall/reinstall.
- [ ] Guardian cannot save an enabled section with no monitored package, duplicate/invalid packages, or a package outside the allowable App Catalog contract.
- [ ] Scam Text Detection is available on Android 8 and later; NSFW Screen Detection is unavailable below Android 11 and cannot be enabled there.
- [ ] Existing schema-v1 and schema-v2 policies decode as both sections disabled, empty monitored-package sets, and Standard sensitivity without beginning monitoring.
- [ ] Convex refuses a schema-v3 policy change until the active Child Device reports schema-v3 support.
- [ ] Guardian save state uses the existing pending, waiting, applied, and failed acknowledgement lifecycle.
- [ ] Child initializes and self-checks every enabled detector through replaceable readiness/session boundaries before acknowledging the candidate policy.
- [ ] Failure of either enabled detector rejects the complete candidate and preserves the previously accepted complete snapshot.
- [ ] Disabling a detector releases its session without deleting its saved package selection or sensitivity; End Supervision releases all safety sessions.
- [ ] Automated tests cover migration defaults, validation, platform availability, schema compatibility, independent selections, atomic acceptance/rejection, prior-snapshot retention, and session lifecycle.
- [ ] Active Screen Safety remains gated out of Play-distributed release variants; this issue does not implement the deferred monitoring-compliance work.

## Blocked by

None - can start immediately

