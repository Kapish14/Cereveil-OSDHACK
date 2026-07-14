package com.cereveil.child.protection

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cereveil.child.enrollment.ChildSafetyAlert
import com.cereveil.child.enrollment.ChildSupervisionPolicy
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Enfold's screen-message detector with only Cereveil policy and reporting adapters. */
internal class EnfoldScamController(
  private val service: AccessibilityService,
  private val scope: CoroutineScope,
) {
  private val classifierMutex = Mutex()
  private val reporter = ChildSafetyAlertReporter(service)
  private val overlayManager = ScamOverlayManager(service)
  private val recentTextHashes = LinkedHashSet<String>()
  private val lastProcessedTime = mutableMapOf<String, Long>()
  private val messagingPackages = setOf(
    "com.whatsapp",
    "com.whatsapp.w4b",
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging",
    service.packageName,
  )

  fun onAccessibilityEvent(event: AccessibilityEvent?, policy: ChildSupervisionPolicy) {
    if (event == null || !policy.activeScreenSafety.scamText.enabled) return
    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

    val packageName = event.packageName?.toString() ?: return
    if (packageName !in messagingPackages ||
      packageName !in policy.activeScreenSafety.scamText.monitoredPackageNames
    ) return

    val now = System.currentTimeMillis()
    val lastTime = lastProcessedTime[packageName] ?: 0L
    if (now - lastTime < DEBOUNCE_MS) return
    lastProcessedTime[packageName] = now

    val root = service.rootInActiveWindow ?: run {
      Log.d(TAG, "rootInActiveWindow is null for $packageName")
      return
    }
    if (!hasEditText(root)) return

    scope.launch(Dispatchers.IO) {
      val messages = extractMessagesFromScreen(root)
      for (message in messages) classifyAndHandle(message, packageName, policy.version)
    }
  }

  private fun hasEditText(node: AccessibilityNodeInfo): Boolean {
    val className = node.className?.toString() ?: ""
    if (className.contains("EditText")) return true
    for (index in 0 until node.childCount) {
      val child = node.getChild(index) ?: continue
      if (hasEditText(child)) return true
    }
    return false
  }

  private fun extractMessagesFromScreen(root: AccessibilityNodeInfo): List<String> {
    val texts = mutableListOf<String>()
    collectTextNodes(root, texts)
    return texts.filter { text ->
      val hash = sha256(text)
      synchronized(recentTextHashes) {
        if (recentTextHashes.contains(hash)) {
          false
        } else {
          if (recentTextHashes.size >= LRU_CACHE_MAX) recentTextHashes.remove(recentTextHashes.first())
          recentTextHashes.add(hash)
          true
        }
      }
    }
  }

  private fun collectTextNodes(node: AccessibilityNodeInfo, texts: MutableList<String>) {
    val className = node.className?.toString() ?: ""
    if (className.contains("TextView") || className.contains("EditText")) {
      val text = node.text?.toString()
      if (text != null && text.length >= MIN_TEXT_LENGTH) texts.add(text)
    }
    for (index in 0 until node.childCount) {
      val child = node.getChild(index) ?: continue
      collectTextNodes(child, texts)
    }
  }

  private suspend fun classifyAndHandle(
    text: String,
    packageName: String,
    policyVersion: Int,
  ) {
    val prediction = try {
      classifierMutex.withLock {
        ChildSafetyModels.classifyScam(
          text,
          com.cereveil.child.enrollment.SafetySensitivity.Standard,
        )
      }
    } catch (error: Exception) {
      Log.e(TAG, "Classification failed", error)
      return
    }

    Log.d(
      TAG,
      "Classified: ${prediction.label} (${(prediction.confidence * 100).toInt()}%) " +
        "action=${if (prediction.isScam) "ALERT" else if (prediction.labelId == 0) "SAFE" else "IGNORE"}",
    )
    if (!prediction.isScam) return

    overlayManager.showWarning()
    reporter.report(ChildSafetyAlert(
      incidentId = UUID.randomUUID().toString(),
      type = "scam_text",
      packageName = packageName,
      confidenceBand = prediction.confidenceBand.name.lowercase(),
      policyVersion = policyVersion,
      occurredAt = System.currentTimeMillis(),
    ))
  }

  fun clear() {
    recentTextHashes.clear()
    lastProcessedTime.clear()
    overlayManager.dismiss()
  }

  fun onPolicyChanged(enabled: Boolean) {
    if (!enabled) {
      recentTextHashes.clear()
      lastProcessedTime.clear()
      overlayManager.dismiss()
    }
  }

  private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray())
    .joinToString("") { "%02x".format(it) }

  private companion object {
    const val TAG = "ScamDetectionService"
    const val DEBOUNCE_MS = 2_000L
    const val LRU_CACHE_MAX = 200
    const val MIN_TEXT_LENGTH = 20
  }
}
