package com.cereveil

import com.cereveil.guardian.auth.GuardianStartupRoute
import junit.framework.TestCase.assertEquals
import org.junit.Test

class GuardianStartupContentTest {
  @Test
  fun guardianStartupDisplayName_mapsRoutes() {
    assertEquals("Sign in required", guardianStartupDisplayName(GuardianStartupRoute.Auth))
    assertEquals("Starting Guardian Mode", guardianStartupDisplayName(GuardianStartupRoute.Loading))
    assertEquals("Guardian setup", guardianStartupDisplayName(GuardianStartupRoute.Setup))
    assertEquals("Guardian dashboard", guardianStartupDisplayName(GuardianStartupRoute.Dashboard))
    assertEquals("Guardian Device revoked", guardianStartupDisplayName(GuardianStartupRoute.DeviceRevoked))
    assertEquals(
      "Guardian Device limit reached",
      guardianStartupDisplayName(GuardianStartupRoute.DeviceLimitReached),
    )
    assertEquals("Connection problem", guardianStartupDisplayName(GuardianStartupRoute.RetryableError))
  }
}
