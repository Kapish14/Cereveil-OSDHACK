Status: ready-for-agent

# Harden Guardian Wrappers and Migrate Post-Bootstrap Guardian APIs

## Parent

.scratch/authenticated-function-wrapper-hardening/PRD.md

## What to build

Complete the post-bootstrap Guardian authentication boundary and carry it end-to-end through Child Profile and Enrollment Code operations. Every wrapped Guardian request must combine Clerk authentication with the locally persisted Guardian installation identity, resolve one complete active GuardianActor, enforce Guardian Account, Household, and Guardian Device lifecycle, and return only safe application errors. Migrate Guardian Mode clients to attach the shared installation identity, route missing local state through Guardian bootstrap, and preserve Guardian bootstrap as the direct mutation that creates or restores the device binding.

This slice also establishes server-owned request correlation and typed privacy-safe logging as shared boundary infrastructure. It must be possible to verify the entire contract by invoking registered Guardian functions rather than testing private helpers.

## Acceptance criteria

- [ ] `guardianQuery` and `guardianMutation` own complete Convex function registration, endpoint validators, the required Guardian installation argument, actor resolution, safe error mapping, correlation, and boundary logging.
- [ ] The wrappers consume the installation argument before invoking application handlers, which receive only the resolved GuardianActor and endpoint-specific input.
- [ ] GuardianActor always contains Guardian Account, Household, and Guardian Device IDs; Guardian Device ID is not optional.
- [ ] Clerk's stable token identifier resolves the Guardian Account, and no client-supplied Guardian Account, Household, or Guardian Device database ID is trusted for actor resolution.
- [ ] Guardian Device resolution matches the installation identity only within the Clerk-authenticated Guardian Account and requires an active device.
- [ ] Missing identity, missing domain state, unknown installation, and foreign installation map to `UNAUTHENTICATED` without revealing whether another record exists.
- [ ] A known revoked Guardian Device maps to `DEVICE_REVOKED`.
- [ ] Disabled and deleting Guardian Accounts map to `ACCOUNT_DISABLED` and `ACCOUNT_DELETING`.
- [ ] A deleting Household maps to `HOUSEHOLD_DELETING`.
- [ ] Unexpected exceptions map to generic `INTERNAL_ERROR` without exposing the original message, stack, database details, or other implementation information.
- [ ] Request IDs are generated server-side and are available for correlation of generic internal failures.
- [ ] Typed logging accepts only the approved request ID, operation, actor kind, outcome, safe error code, and duration fields rather than arbitrary metadata or error objects.
- [ ] All failures and successful Guardian mutations are logged; successful Guardian reads do not create persistent per-request logs.
- [ ] Logs exclude Clerk claims, request arguments, installation IDs, database IDs, Child identity, policy content, and raw exceptions.
- [ ] Child Profile creation and listing use the hardened Guardian wrappers and no longer duplicate Clerk identity or Guardian Account/Household resolution in their use cases.
- [ ] Child Profile work remains scoped to the Household in GuardianActor, and application validation remains distinct from authentication and lifecycle errors.
- [ ] Existing Guardian Enrollment Code creation, cancellation, and status operations adopt the required installation identity and complete GuardianActor contract.
- [ ] Guardian bootstrap remains a directly registered Clerk-authenticated mutation and continues to create or restore the Guardian Device binding.
- [ ] Guardian Android feature clients obtain the installation identity through one shared provider rather than generating identities independently.
- [ ] Missing local installation state produces typed `BootstrapRequired`; bootstrap owns creation and persistence, after which the original operation may be retried once.
- [ ] Existing Child Profile onboarding and Child Device enrollment behavior continues to work with the new Guardian request contract.
- [ ] Public wrapper contract tests cover success, missing Clerk identity, missing installation input, unknown and foreign installations, revoked device, account lifecycle, Household lifecycle, mandatory device actor identity, safe internal errors, correlation, and privacy-safe logs.

## Blocked by

None - can start immediately
