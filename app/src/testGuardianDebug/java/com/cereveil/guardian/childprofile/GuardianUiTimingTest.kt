package com.cereveil.guardian.childprofile

import com.cereveil.guardian.ui.formatUsageDuration
import junit.framework.TestCase.assertEquals
import org.junit.Test

class GuardianUiTimingTest {
  @Test
  fun locationRefreshCooldownCountsDownAndStopsAtZero() {
    val requestedAt = 1_000_000L

    assertEquals(120L, locationRefreshCooldownSeconds(requestedAt, requestedAt))
    assertEquals(60L, locationRefreshCooldownSeconds(requestedAt, requestedAt + 60_001L))
    assertEquals(0L, locationRefreshCooldownSeconds(requestedAt, requestedAt + 120_000L))
    assertEquals(0L, locationRefreshCooldownSeconds(null, requestedAt))
  }

  @Test
  fun usageDurationsStayCompact() {
    assertEquals("12m", formatUsageDuration(12 * 60_000L))
    assertEquals("2h 7m", formatUsageDuration((2 * 60 + 7) * 60_000L))
  }
}
