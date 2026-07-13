package com.cereveil.child.protection

import com.cereveil.child.enrollment.SafetyDetectorPolicy
import java.security.MessageDigest

data class ScamNode(
  val text: String,
  val visible: Boolean,
  val editable: Boolean = false,
  val password: Boolean = false,
  val cereveilOwned: Boolean = false,
)

object ScamCandidatePolicy {
  private val timestamp = Regex("^(?:[01]?\\d|2[0-3]):[0-5]\\d(?:\\s?[ap]m)?$", RegexOption.IGNORE_CASE)
  private val chrome = setOf("reply", "forward", "copy", "delete", "more", "send", "back", "search")

  fun normalize(text: String): String = text
    .replace(Regex("\\s+"), " ")
    .trim()
    .lowercase()

  fun accepts(node: ScamNode, packageName: String, policy: SafetyDetectorPolicy): Boolean {
    if (!policy.enabled || packageName !in policy.monitoredPackageNames) return false
    if (!node.visible || node.editable || node.password || node.cereveilOwned) return false
    val normalized = normalize(node.text)
    if (normalized.length < 20 || timestamp.matches(normalized) || normalized in chrome) return false
    return true
  }
}

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
