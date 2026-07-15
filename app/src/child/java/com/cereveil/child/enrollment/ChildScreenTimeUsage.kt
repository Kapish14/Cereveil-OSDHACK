package com.cereveil.child.enrollment

internal enum class ChildUsageEventKind {
  Foreground,
  Background,
  ScreenInteractive,
  ScreenNonInteractive,
  DeviceShutdown,
}

internal data class ChildUsageEvent(
  val packageName: String,
  val timestamp: Long,
  val kind: ChildUsageEventKind,
)

/**
 * Aggregates only the portion of foreground sessions that overlaps [windowStart, windowEnd].
 * UsageStats daily buckets are deliberately not used because OEM bucket boundaries need not
 * coincide with the Child Device's local midnight.
 */
internal fun aggregateChildScreenTime(
  events: List<ChildUsageEvent>,
  allowedPackages: Set<String>,
  windowStart: Long,
  windowEnd: Long,
): List<ChildScreenTimeRow> {
  require(windowStart <= windowEnd)
  val activePackages = mutableSetOf<String>()
  val totals = mutableMapOf<String, Long>()
  var screenInteractive = true
  var cursor = events.firstOrNull()?.timestamp?.coerceAtMost(windowStart) ?: windowStart

  fun addElapsed(until: Long) {
    val from = cursor.coerceAtLeast(windowStart)
    val to = until.coerceAtMost(windowEnd)
    if (screenInteractive && to > from) {
      val elapsed = to - from
      activePackages.forEach { packageName ->
        totals[packageName] = totals.getOrDefault(packageName, 0) + elapsed
      }
    }
    cursor = until
  }

  events.asSequence()
    .filter { it.timestamp <= windowEnd }
    .sortedBy(ChildUsageEvent::timestamp)
    .forEach { event ->
      addElapsed(event.timestamp)
      when (event.kind) {
        ChildUsageEventKind.Foreground -> if (event.packageName in allowedPackages) {
          activePackages += event.packageName
        }
        ChildUsageEventKind.Background -> activePackages -= event.packageName
        ChildUsageEventKind.ScreenInteractive -> screenInteractive = true
        ChildUsageEventKind.ScreenNonInteractive -> screenInteractive = false
        ChildUsageEventKind.DeviceShutdown -> activePackages.clear()
      }
    }
  addElapsed(windowEnd)

  return totals.entries.asSequence()
    .filter { it.value > 0 }
    .map { ChildScreenTimeRow(it.key, it.value) }
    .sortedByDescending(ChildScreenTimeRow::totalTimeInForegroundMs)
    .take(500)
    .toList()
}
