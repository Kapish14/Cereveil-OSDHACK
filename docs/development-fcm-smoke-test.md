# Development FCM smoke test

Use only the `cereveil-development` Firebase project and the Guardian/Child debug builds. Never copy service-account credentials into either Android build or this document.

1. Install fresh Guardian and Child debug builds on two Android devices. Bootstrap Guardian Mode and enroll Child Mode.
2. Confirm Convex contains one active owner-bound token for each device and no plaintext token. Rotate or reinstall one app and confirm the old binding is no longer active without changing device identity.
3. Allow the test enrollment to cross the guarded 45-minute Offline transition. Confirm Guardian receives a generic `guardian_notice` data wake-up, fetches the authoritative Offline Notice, and acknowledges its own receipt.
4. Send the next authenticated heartbeat. Confirm one correlated Recovery Notice follows. Report a required capability unavailable and confirm one high-priority Tamper Alert; repeat the same heartbeat and confirm no duplicate.
5. Open an enrolled Child Profile's development supervision settings and toggle Screen Time Summaries. Confirm Guardian Mode shows inline progress, Convex creates exactly one complete schema-v1 desired Supervision Policy and supersedes only older pending policy reconciliation, and Child receives only a generic `child_command` wake-up.
6. Confirm Child fetches `apply_policy_version`, fetches the policy separately, atomically activates and persists it as the last accepted policy, and acknowledges both Policy Application State and the command. Confirm the Guardian toggle reaches its final state only after this authoritative acknowledgement.
7. Repeat a Save operation with the same operation identifier and confirm no duplicate version or command. Submit two different changes based on the same version from separate Guardian Devices and confirm one receives a stale-policy conflict rather than overwriting the winner.
8. Put Child Mode Offline, change two unrelated feature controls, and confirm Guardian Mode changes from a spinner to Waiting for Child Device while only the latest complete policy command remains pending. Reconnect and confirm both desired settings apply together.
9. In a controlled development build, force a permanent policy activation failure. Confirm the previous last accepted policy remains in force, Convex records only an allowlisted failed application reason, and Guardian Mode shows Could not apply rather than waiting indefinitely.
10. Disable network or force-stop each app while creating work. Restore network and launch/resume Guardian Mode; allow the Child periodic supervision worker to run. Confirm both recover authoritative records without the missed push.
11. Inspect captured FCM request data: Guardian payload contains only `schemaVersion`, `category`, and opaque `recordId`; Child payload does not contain command type, policy version, or policy content.
12. Uninstall one app, attempt another delivery, and confirm a definitive unregistered response invalidates only that token. Transient failures must retry no more than five attempts over roughly 15 minutes.

Record device/API versions and pass/fail results outside source control. Do not record tokens, Child names, policy data, capabilities, private keys, or authorization headers.
