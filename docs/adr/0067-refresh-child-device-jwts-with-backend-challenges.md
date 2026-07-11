# Refresh Child Device JWTs with backend challenges

Child Device JWT issuance and refresh will use a backend-issued, short-lived, one-use challenge tied to the Child Device Credential. Child Mode signs the challenge with its non-exportable Android Keystore private key, and Device Identity verifies the challenge, credential status, Active Enrollment status, and signature before issuing a new fifteen-minute Child Device JWT, avoiding reusable bearer refresh tokens and replay-prone client-generated nonces.
