package com.cereveil.child.protection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cereveil.child.enrollment.ChildSafetyAlert
import com.cereveil.child.enrollment.ChildSupervisionPolicy
import com.cereveil.child.enrollment.SafetyDetectorPolicy
import com.cereveil.child.ml.ConfidenceBand
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class ActiveScreenSafetyController(
  private val service: AccessibilityService,
  private val scope: CoroutineScope,
  private val handler: Handler,
) {
  private val memory = SafetyIncidentMemory()
  private val reporter = ChildSafetyAlertReporter(service)
  private val windowManager = service.getSystemService(WindowManager::class.java)
  private val powerManager = service.getSystemService(PowerManager::class.java)
  private val keyguardManager = service.getSystemService(KeyguardManager::class.java)
  private val screenshotInFlight = AtomicBoolean(false)
  private val captureSequence = AtomicLong(0)
  private val activeCaptureId = AtomicLong(0)
  private val scamDebounces = mutableMapOf<String, Runnable>()
  private var warningOverlay: View? = null
  private var blurOverlay: View? = null
  private var activeBlurBounds: Rect? = null
  private var activeNsfwPackage: String? = null
  private var activeScanUntil = 0L
  private var captureStartedAt = 0L
  private var cachedImageBounds: Rect? = null
  private var cachedImagePackage: String? = null
  private var cachedImageAt = 0L
  private var lastBlurAt = 0L
  private var lastPolicy: ChildSupervisionPolicy? = null
  private val blurReleaseRunnable = Runnable(::hideBlurNow)
  private val scanRunnable = object : Runnable {
    override fun run() {
      val policy = currentPolicy() ?: return stopNsfw()
      val packageName = activeNsfwPackage ?: return stopNsfw()
      if (!policy.activeScreenSafety.nsfwScreen.enabled ||
        packageName !in policy.activeScreenSafety.nsfwScreen.monitoredPackageNames ||
        !powerManager.isInteractive || keyguardManager.isKeyguardLocked
      ) return stopNsfw()
      val window = monitoredWindow(packageName) ?: return stopNsfw()
      if (screenshotInFlight.get() && System.currentTimeMillis() - captureStartedAt >= CAPTURE_WATCHDOG_MS) {
        activeCaptureId.set(0)
        screenshotInFlight.set(false)
      }
      captureAndClassify(window, packageName, policy)
      val delay = if (System.currentTimeMillis() <= activeScanUntil) ACTIVE_SCAN_MS else IDLE_SCAN_MS
      handler.postDelayed(this, delay)
    }
  }

  fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val policy = currentPolicy() ?: return clearAll()
    val previous = lastPolicy
    if (previous?.activeScreenSafety?.scamText != policy.activeScreenSafety.scamText) {
      scamDebounces.values.forEach(handler::removeCallbacks)
      scamDebounces.clear()
      if (previous?.activeScreenSafety?.scamText?.enabled == true && !policy.activeScreenSafety.scamText.enabled) {
        memory.clear("scam_text")
        hideWarning()
      }
    }
    if (previous?.activeScreenSafety?.nsfwScreen?.enabled == true && !policy.activeScreenSafety.nsfwScreen.enabled) {
      memory.clear("nsfw_screen")
      stopNsfw()
    }
    lastPolicy = policy
    val packageName = event?.packageName?.toString()
    if (packageName != null) {
      if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) invalidateImageNodeCache()
      scheduleScam(packageName, policy.activeScreenSafety.scamText, policy.version)
      val nsfwPackage = if (packageName in policy.activeScreenSafety.nsfwScreen.monitoredPackageNames) packageName
      else service.windows.firstNotNullOfOrNull { window ->
        window.root?.packageName?.toString()?.takeIf {
          it in policy.activeScreenSafety.nsfwScreen.monitoredPackageNames
        }
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        policy.activeScreenSafety.nsfwScreen.enabled && nsfwPackage != null
      ) {
        activeNsfwPackage = nsfwPackage
        activeScanUntil = System.currentTimeMillis() + ACTIVE_WINDOW_MS
        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
      } else {
        stopNsfw()
      }
    }
  }

  private fun scheduleScam(packageName: String, policy: SafetyDetectorPolicy, policyVersion: Int) {
    scamDebounces.remove(packageName)?.let(handler::removeCallbacks)
    if (!policy.enabled || packageName !in policy.monitoredPackageNames) return
    val task = Runnable { classifyVisibleScamNodes(packageName, policy, policyVersion) }
    scamDebounces[packageName] = task
    handler.postDelayed(task, SCAM_DEBOUNCE_MS)
  }

  private fun classifyVisibleScamNodes(packageName: String, policy: SafetyDetectorPolicy, policyVersion: Int) {
    scamDebounces.remove(packageName)
    val current = currentPolicy() ?: return
    if (current.version != policyVersion || current.activeScreenSafety.scamText != policy) return
    val nodes = monitoredWindow(packageName)?.root?.let(::eligibleTextNodes).orEmpty()
    nodes.forEach { text ->
      val normalized = ScamCandidatePolicy.normalize(text)
      scope.launch(Dispatchers.Default) {
        val result = runCatching { ChildSafetyModels.classifyScam(text, policy.sensitivity) }.getOrNull()
          ?: return@launch
        if (!result.isScam || !memory.shouldCreate("scam_text", packageName, normalized)) return@launch
        handler.post { showScamWarning() }
        report(
          type = "scam_text",
          packageName = packageName,
          confidenceBand = result.confidenceBand,
          policyVersion = policyVersion,
        )
      }
    }
  }

  private fun eligibleTextNodes(root: AccessibilityNodeInfo): List<String> {
    val result = mutableListOf<String>()
    val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
    while (stack.isNotEmpty()) {
      val node = stack.removeLast()
      val text = node.text?.toString().orEmpty()
      val packageName = node.packageName?.toString().orEmpty()
      if (ScamCandidatePolicy.accepts(
        ScamNode(
          text = text,
          visible = node.isVisibleToUser,
          editable = node.isEditable,
          password = node.isPassword,
          cereveilOwned = packageName.startsWith("com.cereveil"),
        ),
        packageName,
        lastPolicy?.activeScreenSafety?.scamText ?: return emptyList(),
      )) result += text
      for (index in 0 until node.childCount) node.getChild(index)?.let(stack::add)
    }
    return result.distinct()
  }

  private fun captureAndClassify(
    window: AccessibilityWindowInfo,
    packageName: String,
    policy: ChildSupervisionPolicy,
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !screenshotInFlight.compareAndSet(false, true)) return
    val captureId = captureSequence.incrementAndGet()
    activeCaptureId.set(captureId)
    captureStartedAt = System.currentTimeMillis()
    service.takeScreenshot(0, Executor(handler::post), object : AccessibilityService.TakeScreenshotCallback {
      override fun onSuccess(screenshot: ScreenshotResult) {
        val hardware = screenshot.hardwareBuffer
        if (activeCaptureId.get() != captureId) {
          hardware.close()
          return
        }
        val wrapped = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
        val software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
        hardware.close()
        if (software == null) {
          if (activeCaptureId.compareAndSet(captureId, 0)) screenshotInFlight.set(false)
          return
        }
        val bounds = preferredContentBounds(window, packageName)
          .intersected(Rect(0, 0, software.width, software.height))
        if (bounds.isEmpty) {
          software.recycle()
          if (activeCaptureId.compareAndSet(captureId, 0)) screenshotInFlight.set(false)
          return
        }
        val crop = Bitmap.createBitmap(software, bounds.left, bounds.top, bounds.width(), bounds.height())
        software.recycle()
        scope.launch(Dispatchers.Default) {
          try {
            val result = ChildSafetyModels.classifyNsfw(crop, policy.activeScreenSafety.nsfwScreen.sensitivity)
            val current = currentPolicy()
            if (current?.version != policy.version ||
              packageName !in current.activeScreenSafety.nsfwScreen.monitoredPackageNames ||
              monitoredWindow(packageName) == null
            ) return@launch
            if (result.isNsfw) {
              val fingerprint = perceptualFingerprint(crop)
              val overlaySource = crop.copy(Bitmap.Config.ARGB_8888, false)
              handler.post {
                try { showBlur(overlaySource, bounds) } finally { overlaySource.recycle() }
              }
              if (memory.shouldCreate("nsfw_screen", packageName, fingerprint)) {
                report("nsfw_screen", packageName, result.confidenceBand, policy.version)
              }
            } else handler.post(::hideBlurAfterMinimumHold)
          } catch (_: Exception) {
            // Best effort: platform/model failures never expand capture scope.
          } finally {
            crop.recycle()
            if (activeCaptureId.compareAndSet(captureId, 0)) screenshotInFlight.set(false)
          }
        }
      }

      override fun onFailure(errorCode: Int) {
        if (activeCaptureId.compareAndSet(captureId, 0)) screenshotInFlight.set(false)
      }
    })
  }

  private fun preferredContentBounds(window: AccessibilityWindowInfo, packageName: String): Rect {
    val windowBounds = Rect().also(window::getBoundsInScreen)
    val safeRegions = service.windows
      .filter { it.id != window.id }
      .map { other -> other.type to Rect().also(other::getBoundsInScreen) }
      .filterNot { (type, bounds) ->
        type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && bounds == activeBlurBounds
      }
      .map { it.second }
      .filterNot(Rect::isEmpty)
      .fold(listOf(windowBounds), ::subtractBounds)
    val fallback = safeRegions.maxByOrNull(::area) ?: return Rect()
    val now = System.currentTimeMillis()
    val refreshMs = if (now <= activeScanUntil) ACTIVE_IMAGE_CACHE_REFRESH_MS else IMAGE_CACHE_REFRESH_MS
    cachedImageBounds?.takeIf {
      cachedImagePackage == packageName && now - cachedImageAt < refreshMs
    }?.let { cached ->
      return safeRegions.map { Rect(cached).apply { intersect(it) } }.maxByOrNull(::area)
        ?.takeUnless(Rect::isEmpty) ?: fallback
    }
    val root = window.root ?: return fallback
    var largest: Rect? = null
    val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
    while (stack.isNotEmpty()) {
      val node = stack.removeLast()
      if (node.isVisibleToUser && node.className?.toString()?.contains("Image", true) == true) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val largestArea = largest?.let { it.width() * it.height() } ?: 0
        if (!bounds.isEmpty && bounds.width() * bounds.height() > largestArea) {
          largest = bounds
        }
      }
      for (index in 0 until node.childCount) node.getChild(index)?.let(stack::add)
    }
    cachedImageBounds = largest?.let(::Rect)
    cachedImagePackage = packageName
    cachedImageAt = now
    return largest?.let { imageBounds ->
      safeRegions.map { Rect(imageBounds).apply { intersect(it) } }.maxByOrNull(::area)
        ?.takeUnless(Rect::isEmpty)
    } ?: fallback
  }

  private fun subtractBounds(regions: List<Rect>, occlusion: Rect): List<Rect> = regions.flatMap { region ->
    val overlap = Rect(region)
    if (!overlap.intersect(occlusion)) return@flatMap listOf(region)
    listOf(
      Rect(region.left, region.top, region.right, overlap.top),
      Rect(region.left, overlap.bottom, region.right, region.bottom),
      Rect(region.left, overlap.top, overlap.left, overlap.bottom),
      Rect(overlap.right, overlap.top, region.right, overlap.bottom),
    ).filterNot(Rect::isEmpty)
  }

  private fun area(rect: Rect): Int = rect.width() * rect.height()

  private fun invalidateImageNodeCache() {
    cachedImageBounds = null
    cachedImagePackage = null
    cachedImageAt = 0L
  }

  private fun monitoredWindow(packageName: String): AccessibilityWindowInfo? = service.windows
    .firstOrNull { window ->
      window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
        window.root?.packageName?.toString() == packageName
    }

  private fun showScamWarning() {
    hideWarning()
    val content = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(32, 24, 32, 24)
      setBackgroundColor(Color.rgb(120, 36, 24))
      addView(TextView(service).apply {
        text = "This message may be a scam. Don’t share passwords, OTPs, or payment details."
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
      })
      addView(Button(service).apply { text = "I understand"; setOnClickListener { hideWarning() } })
    }
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.BOTTOM }
    windowManager.addView(content, params)
    warningOverlay = content
    handler.postDelayed(::hideWarning, WARNING_DURATION_MS)
  }

  private fun showBlur(bitmap: Bitmap, bounds: Rect) {
    hideBlurNow()
    val tiny = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
    val pixelated = Bitmap.createScaledBitmap(tiny, bounds.width(), bounds.height(), false)
    tiny.recycle()
    val image = ImageView(service).apply {
      scaleType = ImageView.ScaleType.FIT_XY
      setImageBitmap(pixelated)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setRenderEffect(android.graphics.RenderEffect.createBlurEffect(28f, 28f, android.graphics.Shader.TileMode.CLAMP))
      }
    }
    val params = WindowManager.LayoutParams(
      bounds.width(), bounds.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.OPAQUE,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = bounds.left; y = bounds.top }
    windowManager.addView(image, params)
    blurOverlay = image
    activeBlurBounds = Rect(bounds)
    lastBlurAt = System.currentTimeMillis()
  }

  private fun hideWarning() {
    warningOverlay?.let { runCatching { windowManager.removeView(it) } }
    warningOverlay = null
  }

  private fun hideBlurAfterMinimumHold() {
    val remaining = (lastBlurAt + MIN_BLUR_HOLD_MS - System.currentTimeMillis()).coerceAtLeast(0L)
    handler.removeCallbacks(blurReleaseRunnable)
    handler.postDelayed(blurReleaseRunnable, remaining)
  }

  private fun hideBlurNow() {
    handler.removeCallbacks(blurReleaseRunnable)
    ((blurOverlay as? ImageView)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
    (blurOverlay as? ImageView)?.drawable?.callback = null
    blurOverlay?.let { runCatching { windowManager.removeView(it) } }
    blurOverlay = null
    activeBlurBounds = null
  }

  private fun stopNsfw() {
    activeNsfwPackage = null
    handler.removeCallbacks(scanRunnable)
    invalidateImageNodeCache()
    hideBlurNow()
  }

  fun clearAll() {
    scamDebounces.values.forEach(handler::removeCallbacks)
    scamDebounces.clear()
    handler.removeCallbacks(scanRunnable)
    hideWarning()
    hideBlurNow()
    activeNsfwPackage = null
    memory.clear()
  }

  private fun currentPolicy(): ChildSupervisionPolicy? = service
    .getSharedPreferences("child_policy_runtime", AccessibilityService.MODE_PRIVATE)
    .getString("policy", null)
    ?.let { runCatching { ChildSupervisionPolicy.parse(it) }.getOrNull() }

  private fun report(type: String, packageName: String, confidenceBand: ConfidenceBand, policyVersion: Int) {
    scope.launch(Dispatchers.IO) {
      reporter.report(ChildSafetyAlert(
        incidentId = UUID.randomUUID().toString(),
        type = type,
        packageName = packageName,
        confidenceBand = confidenceBand.name.lowercase(),
        policyVersion = policyVersion,
        occurredAt = System.currentTimeMillis(),
      ))
    }
  }

  private fun perceptualFingerprint(bitmap: Bitmap): String {
    val sample = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
    val pixels = IntArray(64)
    sample.getPixels(pixels, 0, 8, 0, 0, 8, 8)
    sample.recycle()
    val luminance = pixels.map { Color.red(it) * 3 + Color.green(it) * 6 + Color.blue(it) }
    val average = luminance.average()
    return luminance.joinToString("") { if (it >= average) "1" else "0" }
  }

  private fun Rect.intersected(other: Rect): Rect = Rect(this).apply { intersect(other) }

  private companion object {
    const val SCAM_DEBOUNCE_MS = 2_000L
    const val WARNING_DURATION_MS = 15_000L
    const val ACTIVE_SCAN_MS = 200L
    const val IDLE_SCAN_MS = 1_000L
    const val ACTIVE_WINDOW_MS = 2_000L
    const val IMAGE_CACHE_REFRESH_MS = 450L
    const val ACTIVE_IMAGE_CACHE_REFRESH_MS = 900L
    const val CAPTURE_WATCHDOG_MS = 30_000L
    const val MIN_BLUR_HOLD_MS = 2_500L
  }
}
