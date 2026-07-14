package com.cereveil.guardian.auth

internal fun resolveGuardianAuthState(
  clerkInitialized: Boolean,
  clerkInitializationFailed: Boolean,
  authSessionKey: String?,
  internetAvailable: Boolean,
): GuardianAuthState? =
  when {
    authSessionKey != null -> GuardianAuthState.Authenticated(authSessionKey)
    !internetAvailable -> GuardianAuthState.TemporarilyUnavailable
    !clerkInitialized && !clerkInitializationFailed -> null
    else -> GuardianAuthState.Unauthenticated
  }
