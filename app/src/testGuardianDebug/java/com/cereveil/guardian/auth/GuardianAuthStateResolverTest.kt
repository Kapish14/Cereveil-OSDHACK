package com.cereveil.guardian.auth

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class GuardianAuthStateResolverTest {
  @Test
  fun offlineWithoutRestoredSessionIsUnavailableInsteadOfSignedOut() {
    assertEquals(
      GuardianAuthState.TemporarilyUnavailable,
      resolveGuardianAuthState(
        clerkInitialized = true,
        clerkInitializationFailed = false,
        authSessionKey = null,
        internetAvailable = false,
      ),
    )
  }

  @Test
  fun authenticatedSessionWinsAfterConnectivityDrops() {
    assertEquals(
      GuardianAuthState.Authenticated("guardian-user"),
      resolveGuardianAuthState(
        clerkInitialized = true,
        clerkInitializationFailed = false,
        authSessionKey = "guardian-user",
        internetAvailable = false,
      ),
    )
  }

  @Test
  fun onlineWithoutSessionIsSignedOutAfterClerkFinishes() {
    assertEquals(
      GuardianAuthState.Unauthenticated,
      resolveGuardianAuthState(
        clerkInitialized = true,
        clerkInitializationFailed = false,
        authSessionKey = null,
        internetAvailable = true,
      ),
    )
  }

  @Test
  fun onlineClerkInitializationRemainsLoading() {
    assertNull(
      resolveGuardianAuthState(
        clerkInitialized = false,
        clerkInitializationFailed = false,
        authSessionKey = null,
        internetAvailable = true,
      ),
    )
  }
}
