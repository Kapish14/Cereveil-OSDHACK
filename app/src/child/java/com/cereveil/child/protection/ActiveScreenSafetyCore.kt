package com.cereveil.child.protection

import java.security.MessageDigest

class SafetyIncidentMemory(
  private val ttlMs: Long = 10 * 60 * 1000L,
  private val now: () -> Long = System::currentTimeMillis,
) {
  private val seenAt = mutableMapOf<String, Long>()

  @Synchronized
  fun shouldCreate(detectorType: String, packageName: String, itemFingerprintSource: String): Boolean {
    val current = now()
    seenAt.entries.removeAll { current - it.value >= ttlMs }
    val key = "$detectorType|$packageName|${sha256(itemFingerprintSource)}"
    val prior = seenAt[key]
    if (prior != null && current - prior < ttlMs) return false
    seenAt[key] = current
    return true
  }

  @Synchronized
  fun clear(detectorType: String? = null) {
    if (detectorType == null) seenAt.clear()
    else seenAt.keys.removeAll { it.startsWith("$detectorType|") }
  }

  private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
}
