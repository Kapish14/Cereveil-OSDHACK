# Development FCM smoke test

Use only the `cereveil-development` Firebase project and the Guardian/Child debug builds. Never copy service-account credentials into either Android build or this document.

1. Install fresh Guardian and Child debug builds on two Android devices. Bootstrap Guardian Mode and enroll Child Mode.
2. Confirm Convex contains one active owner-bound token for each device and no plaintext token. Rotate or reinstall one app and confirm the old binding is no longer active without changing device identity.
3. Allow the test enrollment to cross the guarded 45-minute Offline transition. Confirm Guardian receives a generic `guardian_notice` data wake-up, fetches the authoritative Offline Notice, and acknowledges its own receipt.
4. Send the next authenticated heartbeat. Confirm one correlated Recovery Notice follows. Report a required capability unavailable and confirm one high-priority Tamper Alert; repeat the same heartbeat and confirm no duplicate.
5. Create a newer desired Supervision Policy. Confirm Child receives only a generic `child_command` wake-up, fetches `apply_policy_version`, fetches the policy separately, applies it, and acknowledges both Policy Application State and the command.
6. Disable network or force-stop each app while creating work. Restore network and launch/resume Guardian Mode; allow the Child periodic supervision worker to run. Confirm both recover authoritative records without the missed push.
7. Inspect captured FCM request data: Guardian payload contains only `schemaVersion`, `category`, and opaque `recordId`; Child payload does not contain command type, policy version, or policy content.
8. Uninstall one app, attempt another delivery, and confirm a definitive unregistered response invalidates only that token. Transient failures must retry no more than five attempts over roughly 15 minutes.

Record device/API versions and pass/fail results outside source control. Do not record tokens, Child names, policy data, capabilities, private keys, or authorization headers.
