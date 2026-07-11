Status: ready-for-agent

# Persist Guardian installation identity and metadata

## What to build

Build the local Guardian Mode path that creates and reuses a `guardianInstallationId`, preserves it across app restarts and normal sign-out, and prepares the app/device facts needed for Guardian auth bootstrap. This slice should make the app able to produce a backend-safe installation ID, a readable device label from brand/model, app build metadata, and the current IANA timezone without using hardware identifiers or location data.

## Acceptance criteria

- [ ] First use generates a random opaque `guardianInstallationId` and persists it locally.
- [ ] Later launches reuse the existing `guardianInstallationId` instead of generating a new one.
- [ ] Normal Guardian sign-out clears account-specific bootstrap state, if present, without deleting `guardianInstallationId`.
- [ ] The generated installation ID is not derived from IMEI, Android ID, advertising ID, serial number, phone number, MAC address, or any other hardware identifier.
- [ ] Device label building handles normal brand/model values, blank values, unknown values, and duplicated brand/model values.
- [ ] App build metadata includes the version/build facts expected by the backend bootstrap contract.
- [ ] Timezone metadata uses the device's current IANA timezone and does not require location permission.
- [ ] Focused JVM tests cover installation ID generation/reuse, sign-out preservation, device label normalization, app build metadata, and timezone behavior.

## Blocked by

None - can start immediately
