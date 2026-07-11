# Use Device Identity HTTP endpoints for Child enrollment

Child enrollment preview, completion, and Child Device JWT issuance will use Device Identity HTTP endpoints rather than normal authenticated Convex mutations. Child Mode has no Convex auth identity before enrollment, and the Device Identity boundary owns Enrollment Code validation, Keystore proof verification, Child Device Credential creation, and short-lived JWT issuance while delegating atomic database changes to internal Convex mutations.
