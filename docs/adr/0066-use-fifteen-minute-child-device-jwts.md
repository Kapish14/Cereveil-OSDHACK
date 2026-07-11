# Use fifteen-minute Child Device JWTs

Child Device JWTs will live for fifteen minutes in v1. This keeps bearer-token exposure bounded after credential or enrollment revocation while avoiding excessive Keystore challenge-signing refresh traffic during normal Child Mode operation; sensitive backend operations still resolve current credential and Active Enrollment state rather than relying only on token expiry.
