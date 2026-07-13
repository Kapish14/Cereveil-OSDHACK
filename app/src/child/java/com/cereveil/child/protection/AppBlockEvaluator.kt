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
  fun nextTransition(
    packageNames: Collection<String>,
    policy: AppBlockingPolicy,
    now: Instant,
    zoneId: ZoneId,
    grants: List<LocalAccessGrant>,
  ): Instant? {
    val transitions = mutableListOf<Instant>()
    grants.filter { it.packageName in packageNames && it.expiresAt > now }.forEach { transitions += it.expiresAt }
    if (!policy.enabled) return transitions.minOrNull()
    val localDate = now.atZone(zoneId).toLocalDate()
    policy.rules.filter { it.packageName in packageNames }.forEach { rule ->
      for (offset in -1L..8L) {
        val date = localDate.plusDays(offset)
        rule.schedules.filter { date.dayOfWeek.value in it.weekdays }.forEach { schedule ->
          val start = date.atTime(schedule.startMinute / 60, schedule.startMinute % 60).atZone(zoneId).toInstant()
          val endDate = if (schedule.endMinute < schedule.startMinute) date.plusDays(1) else date
          val end = endDate.atTime(schedule.endMinute / 60, schedule.endMinute % 60).atZone(zoneId).toInstant()
          if (start > now) transitions += start
          if (end > now) transitions += end
        }
      }
    }
    return transitions.minOrNull()
  }

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
          val start = date.atTime(schedule.startMinute / 60, schedule.startMinute % 60).atZone(zoneId)
          val endDate = if (schedule.endMinute < schedule.startMinute) date.plusDays(1) else date
          val end = endDate.atTime(schedule.endMinute / 60, schedule.endMinute % 60).atZone(zoneId)
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
