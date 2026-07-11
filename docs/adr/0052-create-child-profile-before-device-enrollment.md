# Create Child Profile before device enrollment

Cereveil will let Guardian Mode create a Child Profile before any Child Device is enrolled. Creating a Child Profile stores only minimal child identity and creates an initial Supervision Policy version with privacy-default settings, but it does not create an Active Enrollment or Child Device Credential. Active Enrollment begins only after a Prepared Child Device completes Protection Setup and exchanges a valid Enrollment Code. This keeps child identity, policy configuration, and device pairing as separate lifecycle steps.
