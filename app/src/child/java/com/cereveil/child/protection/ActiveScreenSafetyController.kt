package com.cereveil.child.protection

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
import com.cereveil.child.enrollment.ChildSupervisionPolicy
import kotlinx.coroutines.CoroutineScope

/** Hosts the detector implementations ported from Enfold and Revive. */
internal class ActiveScreenSafetyController(
  private val service: AccessibilityService,
  scope: CoroutineScope,
  handler: Handler,
) {
  private val scam = EnfoldScamController(service, scope)
  private val nsfw = ReviveNsfwController(service, scope, handler)

  fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val policy = currentPolicy() ?: return clearAll()
    scam.onAccessibilityEvent(event, policy)
    nsfw.onAccessibilityEvent(event, policy)
  }

  fun recheckVisibleWindows() {
    val policy = currentPolicy() ?: return clearAll()
    scam.onPolicyChanged(policy.activeScreenSafety.scamText.enabled)
    nsfw.recheckVisibleWindows()
  }

  fun clearAll() {
    scam.clear()
    nsfw.clear()
  }

  private fun currentPolicy(): ChildSupervisionPolicy? = service
    .getSharedPreferences("child_policy_runtime", AccessibilityService.MODE_PRIVATE)
    .getString("policy", null)
    ?.let { runCatching { ChildSupervisionPolicy.parse(it) }.getOrNull() }
}
