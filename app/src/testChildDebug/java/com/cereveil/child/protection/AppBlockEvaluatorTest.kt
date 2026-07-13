package com.cereveil.child.protection

import com.cereveil.child.enrollment.AppBlockRule
import com.cereveil.child.enrollment.AppBlockSchedule
import com.cereveil.child.enrollment.AppBlockingPolicy
import java.time.Instant
import java.time.ZoneId
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class AppBlockEvaluatorTest {
  private val zone = ZoneId.of("Asia/Kolkata")

  @Test
  fun manualBlockWinsUntilAnAbsoluteGrantExpires() {
    val policy = AppBlockingPolicy(true, listOf(AppBlockRule("com.example.reader", true, emptyList())))
    val now = Instant.parse("2026-07-13T06:30:00Z")

    assertTrue(AppBlockEvaluator.evaluate("com.example.reader", policy, now, zone, emptyList()) is EffectiveAppBlock.Manual)
    assertNull(AppBlockEvaluator.evaluate(
      "com.example.reader",
      policy,
      now,
      zone,
      listOf(LocalAccessGrant("com.example.reader", now.minusSeconds(60), now.plusSeconds(60))),
    ))
    assertTrue(AppBlockEvaluator.evaluate(
      "com.example.reader",
      policy,
      now.plusSeconds(61),
      zone,
      listOf(LocalAccessGrant("com.example.reader", now.minusSeconds(60), now.plusSeconds(60))),
    ) is EffectiveAppBlock.Manual)
  }

  @Test
  fun overnightAndOverlappingSchedulesProduceContinuousCoverageEnd() {
    val policy = AppBlockingPolicy(true, listOf(AppBlockRule(
      "com.example.reader",
      false,
      listOf(
        AppBlockSchedule("bedtime", setOf(1), 21 * 60, 7 * 60),
        AppBlockSchedule("morning", setOf(2), 6 * 60 + 30, 8 * 60),
      ),
    )))
    val now = Instant.parse("2026-07-14T01:30:00Z") // Tuesday 07:00 in Kolkata.

    val block = AppBlockEvaluator.evaluate("com.example.reader", policy, now, zone, emptyList())

    assertTrue(block is EffectiveAppBlock.Scheduled)
    assertEquals(Instant.parse("2026-07-14T02:30:00Z"), (block as EffectiveAppBlock.Scheduled).coverageEndsAt)
  }

  @Test
  fun daylightSavingScheduleUsesLocalCivilTimeInsteadOfElapsedMinutesFromMidnight() {
    val newYork = ZoneId.of("America/New_York")
    val policy = AppBlockingPolicy(true, listOf(AppBlockRule(
      "com.example.reader",
      false,
      listOf(AppBlockSchedule("sunday-morning", setOf(7), 7 * 60, 8 * 60)),
    )))
    val now = Instant.parse("2026-03-08T11:30:00Z") // 07:30 after the spring-forward gap.

    assertTrue(AppBlockEvaluator.evaluate(
      "com.example.reader", policy, now, newYork, emptyList(),
    ) is EffectiveAppBlock.Scheduled)
  }
}
