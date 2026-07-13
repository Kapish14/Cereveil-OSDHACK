package com.cereveil.child.protection

import com.cereveil.child.enrollment.AppBlockingPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class LocalAccessGrant(
  val packageName: String,
  val startsAt: Instant,
  val expiresAt: Instant,
)

sealed interface EffectiveAppBlock {
  data object Manual : EffectiveAppBlock
  data class Scheduled(val coverageEndsAt: Instant) : EffectiveAppBlock
}

object AppBlockEvaluator {
  fun evaluate(
    packageName: String,
    policy: AppBlockingPolicy,
    now: Instant,
    zoneId: ZoneId,
    grants: List<LocalAccessGrant>,
  ): EffectiveAppBlock? {
    if (!policy.enabled) return null
    if (grants.any { it.packageName == packageName && !now.isBefore(it.startsAt) && now.isBefore(it.expiresAt) }) {
      return null
    }
    val rule = policy.rules.firstOrNull { it.packageName == packageName } ?: return null
    if (rule.manualBlocked) return EffectiveAppBlock.Manual
    val localNow = now.atZone(zoneId)
    val intervals = buildList {
      for (offset in -1L..8L) {
        val date = localNow.toLocalDate().plusDays(offset)
        for (schedule in rule.schedules) {
          if (date.dayOfWeek.value !in schedule.weekdays) continue
          val start = date.atStartOfDay(zoneId).plusMinutes(schedule.startMinute.toLong())
          val endDate = if (schedule.endMinute < schedule.startMinute) date.plusDays(1) else date
          val end = endDate.atStartOfDay(zoneId).plusMinutes(schedule.endMinute.toLong())
          add(start.toInstant() to end.toInstant())
        }
      }
    }.sortedBy { it.first }
    val active = intervals.firstOrNull { !now.isBefore(it.first) && now.isBefore(it.second) } ?: return null
    var coverageEnd = active.second
    for ((start, end) in intervals) {
      if (start > coverageEnd) break
      if (end > coverageEnd && !start.isAfter(coverageEnd)) coverageEnd = end
    }
    return EffectiveAppBlock.Scheduled(coverageEnd)
  }
}
