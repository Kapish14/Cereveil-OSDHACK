package com.cereveil.child.enrollment

import junit.framework.TestCase.assertEquals
import org.junit.Test

class ChildScreenTimeUsageTest {
  @Test
  fun reportsOnlyForegroundTimeInsideTheCurrentLocalDay() {
    val rows = aggregateChildScreenTime(
      events = listOf(
        event("youtube", 100, ChildUsageEventKind.Foreground),
        event("youtube", 900, ChildUsageEventKind.Background),
        event("youtube", 1_500, ChildUsageEventKind.Foreground),
        event("youtube", 2_500, ChildUsageEventKind.Background),
      ),
      allowedPackages = setOf("youtube"),
      windowStart = 1_000,
      windowEnd = 4_000,
    )

    assertEquals(listOf(ChildScreenTimeRow("youtube", 1_000)), rows)
  }

  @Test
  fun excludesScreenOffTimeAndClosesAStillForegroundSessionAtMeasurementTime() {
    val rows = aggregateChildScreenTime(
      events = listOf(
        event("chrome", 1_200, ChildUsageEventKind.Foreground),
        event("", 1_800, ChildUsageEventKind.ScreenNonInteractive),
        event("", 2_800, ChildUsageEventKind.ScreenInteractive),
        event("x", 3_200, ChildUsageEventKind.Foreground),
        event("chrome", 3_500, ChildUsageEventKind.Background),
      ),
      allowedPackages = setOf("chrome", "x"),
      windowStart = 1_000,
      windowEnd = 4_000,
    )

    assertEquals(
      listOf(
        ChildScreenTimeRow("chrome", 1_300),
        ChildScreenTimeRow("x", 800),
      ),
      rows,
    )
  }

  private fun event(packageName: String, timestamp: Long, kind: ChildUsageEventKind) =
    ChildUsageEvent(packageName, timestamp, kind)
}
