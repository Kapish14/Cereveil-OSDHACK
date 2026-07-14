package com.cereveil.guardian.auth

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GuardianStartupRefreshPolicyTest {
  @Test
  fun dashboardRemainsVisibleDuringBackgroundAuthRevalidation() {
    assertEquals(
      GuardianStartupRoute.Dashboard,
      routeWhileRefreshing(GuardianStartupRoute.Dashboard),
    )
    assertEquals(
      GuardianStartupRoute.Setup,
      routeWhileRefreshing(GuardianStartupRoute.Setup),
    )
  }

  @Test
  fun duplicateAuthenticatedEmissionsDoNotRestartGuardian() = runTest {
    val authenticated = GuardianAuthState.Authenticated("same-user")

    assertEquals(
      listOf(authenticated),
      flowOf(authenticated, authenticated, authenticated).stableAuthStates().toList(),
    )
  }
}
