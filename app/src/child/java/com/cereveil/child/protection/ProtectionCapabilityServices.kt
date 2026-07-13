package com.cereveil.child.protection

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.net.VpnService
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cereveil.BuildConfig
import com.cereveil.child.enrollment.AndroidChildDeviceKeyStore
import com.cereveil.child.enrollment.ChildDeviceTokenProvider
import com.cereveil.child.enrollment.ChildEnrollmentResult
import com.cereveil.child.enrollment.ChildSupervisionPolicy
import com.cereveil.child.enrollment.HttpChildDeviceIdentityClient
import com.cereveil.child.enrollment.SharedPreferencesChildEnrollmentStateStore
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CereveilAccessibilityService : AccessibilityService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var overlay: View? = null
  private var overlayPackage: String? = null
  private var askButton: Button? = null
  private val handler = Handler(Looper.getMainLooper())
  private val boundaryCheck = object : Runnable {
    override fun run() {
      evaluateVisibleApps(null)
    }
  }

  override fun onServiceConnected() {
    instance = this
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    evaluateVisibleApps(event?.packageName?.toString())
  }

  private fun evaluateVisibleApps(eventPackage: String?) {
    val policy = getSharedPreferences("child_policy_runtime", MODE_PRIVATE)
      .getString("policy", null)
      ?.let { runCatching { ChildSupervisionPolicy.parse(it) }.getOrNull() }
      ?: return hideOverlay()
    val now = Instant.now()
    val grants = ChildAccessGrantStore(this).active(now)
    val visiblePackages = windows.asSequence()
      .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
      .mapNotNull { it.root?.packageName?.toString() }
      .distinct()
      .filterNot(::isExemptPackage)
      .toMutableList()
    eventPackage?.takeUnless(::isExemptPackage)?.let { if (it !in visiblePackages) visiblePackages += it }
    val blocked = visiblePackages.asSequence()
      .mapNotNull { packageName ->
        AppBlockEvaluator.evaluate(packageName, policy.appBlocking, now, ZoneId.systemDefault(), grants)
          ?.let { packageName to it }
      }
      .firstOrNull()
    if (blocked === null) hideOverlay() else showOverlay(blocked.first, blocked.second, policy.version)
    handler.removeCallbacks(boundaryCheck)
    AppBlockEvaluator.nextTransition(visiblePackages, policy.appBlocking, now, ZoneId.systemDefault(), grants)?.let {
      handler.postDelayed(boundaryCheck, (it.toEpochMilli() - System.currentTimeMillis() + 50).coerceAtLeast(50))
    }
  }

  override fun onInterrupt() = hideOverlay()

  override fun onDestroy() {
    handler.removeCallbacks(boundaryCheck)
    if (instance === this) instance = null
    hideOverlay()
    scope.cancel()
    super.onDestroy()
  }

  private fun showOverlay(packageName: String, block: EffectiveAppBlock, policyVersion: Int) {
    if (overlayPackage == packageName && overlay != null) return
    hideOverlay()
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(48, 48, 48, 48)
      setBackgroundColor(Color.rgb(16, 20, 28))
    }
    content.addView(TextView(this).apply {
      text = "This app is blocked"
      textSize = 28f
      setTextColor(Color.WHITE)
      gravity = Gravity.CENTER
    })
    content.addView(TextView(this).apply {
      text = if (block is EffectiveAppBlock.Manual) {
        "Your Guardian has blocked this app until the rule is removed."
      } else {
        "This app is unavailable during its scheduled block."
      }
      textSize = 17f
      setTextColor(Color.LTGRAY)
      gravity = Gravity.CENTER
    })
    content.addView(Button(this).apply {
      text = "Ask Guardian"
      setOnClickListener {
        isEnabled = false
        text = "Sending request…"
        requestAccess(packageName, block, policyVersion, this)
      }
      askButton = this
    })
    content.addView(Button(this).apply {
      text = "Go Home"
      setOnClickListener { performGlobalAction(GLOBAL_ACTION_HOME) }
    })
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      android.graphics.PixelFormat.OPAQUE,
    )
    getSystemService(WindowManager::class.java).addView(content, params)
    overlay = content
    overlayPackage = packageName
  }

  private fun requestAccess(packageName: String, block: EffectiveAppBlock, policyVersion: Int, button: Button) {
    scope.launch {
      val store = SharedPreferencesChildEnrollmentStateStore(this@CereveilAccessibilityService)
      val state = store.load()
      val client = HttpChildDeviceIdentityClient(BuildConfig.CONVEX_SITE_URL)
      val token = when {
        state == null -> null
        state.accessJwtExpiresAt > System.currentTimeMillis() + 30_000 -> state.accessJwt
        else -> when (val refreshed = ChildDeviceTokenProvider(client, AndroidChildDeviceKeyStore(), store).refresh()) {
          is ChildEnrollmentResult.Success -> refreshed.value
          is ChildEnrollmentResult.Failure -> null
        }
      }
      val result = token?.let {
        client.createAccessRequest(
          accessJwt = it,
          packageName = packageName,
          appliedPolicyVersion = policyVersion,
          blockKind = if (block is EffectiveAppBlock.Manual) "manual" else "scheduled",
          scheduledCoverageEnd = (block as? EffectiveAppBlock.Scheduled)?.coverageEndsAt?.toEpochMilli(),
        )
      }
      button.text = if (result is ChildEnrollmentResult.Success) "Request sent" else "Guardian unreachable"
      button.isEnabled = result !is ChildEnrollmentResult.Success
    }
  }

  private fun hideOverlay() {
    overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
    overlay = null
    overlayPackage = null
    askButton = null
  }

  private fun isExemptPackage(packageName: String) = ChildExemptApps.isExempt(this, packageName)

  companion object {
    @Volatile private var instance: CereveilAccessibilityService? = null

    fun requestReevaluation(resetRequestPresentation: Boolean = false) {
      instance?.let { service ->
        service.handler.post {
          if (resetRequestPresentation) service.hideOverlay()
          service.evaluateVisibleApps(null)
        }
      }
    }

    fun showDenied(retryAt: Long) {
      instance?.let { service ->
        service.handler.post {
          val button = service.askButton ?: return@post
          button.isEnabled = false
          button.text = "Guardian said no • try again soon"
          service.handler.postDelayed({
            service.hideOverlay()
            service.evaluateVisibleApps(null)
          }, (retryAt - System.currentTimeMillis()).coerceAtLeast(1_000))
        }
      }
    }
  }
}

class CereveilNotificationListenerService : NotificationListenerService()

class CereveilVpnService : VpnService()
