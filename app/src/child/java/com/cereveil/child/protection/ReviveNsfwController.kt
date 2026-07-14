package com.cereveil.child.protection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cereveil.child.enrollment.ChildSafetyAlert
import com.cereveil.child.enrollment.ChildSupervisionPolicy
import com.cereveil.child.enrollment.SafetySensitivity
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Revive's image-region scan/overlay pipeline with Cereveil policy and reporting adapters. */
internal class ReviveNsfwController(
  private val service: AccessibilityService,
  private val scope: CoroutineScope,
  private val handler: Handler,
) {
  private val reporter = ChildSafetyAlertReporter(service)
  private val incidentMemory = SafetyIncidentMemory()
  private val powerManager = service.getSystemService(PowerManager::class.java)
  private val overlayManager = OverlayManager(
    context = service,
    shouldShowFalsePositiveAction = { true },
    onFalsePositiveDismiss = ::handleManualBlurDismiss,
  )

  private var screenshotRunnable: Runnable? = null
  private var isCapturing = false
  private var nextCaptureId = 0L
  private var activeCaptureId = 0L
  private var regionScanStartIndex = 0
  private var lastFullRegionScanTimeMs = 0L
  private var cachedRegionPackageName = ""
  private data class CachedImageNode(val node: AccessibilityNodeInfo, var lastSeenMs: Long)
  private val cachedImageNodes = mutableListOf<CachedImageNode>()
  private var lastTouchTime = 0L
  private var currentForegroundPackage = ""
  private var overlaySourcePackage = ""
  private var manualBlurSnoozePackage = ""
  private var manualBlurSnoozeUntilMs = 0L
  private var lastScrollDeltaY = 0
  private var lastScrollEventTimeMs = 0L

  fun recheckVisibleWindows() {
    syncForegroundPackage(null)
    val policy = currentPolicy()
    if (policy?.activeScreenSafety?.nsfwScreen?.enabled == true) startScreenshotLoop()
    else {
      stopScreenshotLoop()
      dismissActiveBlurOverlay()
      clearCachedImageNodes()
    }
  }

  fun onAccessibilityEvent(event: AccessibilityEvent?, policy: ChildSupervisionPolicy) {
    event ?: return
    if (!policy.activeScreenSafety.nsfwScreen.enabled) {
      stopScreenshotLoop()
      overlayManager.dismiss()
      return
    }
    if (screenshotRunnable == null) startScreenshotLoop()

    when (event.eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
        lastTouchTime = System.currentTimeMillis()
        syncForegroundPackage(event)
      }
      AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
        lastTouchTime = System.currentTimeMillis()
        lastScrollEventTimeMs = System.currentTimeMillis()
        syncForegroundPackage(event)
        if (event.scrollDeltaY != 0 && event.scrollDeltaY != Int.MIN_VALUE) {
          lastScrollDeltaY = event.scrollDeltaY
        }
        if (overlayManager.isShowing) {
          val deltaY = event.scrollDeltaY
          if (deltaY != 0 && deltaY != Int.MIN_VALUE) {
            val screenHeight = service.resources.displayMetrics.heightPixels
            val absoluteDelta = kotlin.math.abs(deltaY)
            val dismissThreshold = (screenHeight * OVERLAY_DISMISS_SCROLL_FRACTION).toInt()
            if (absoluteDelta >= dismissThreshold) {
              dismissActiveBlurOverlay()
            } else if (absoluteDelta <= screenHeight) {
              overlayManager.offsetOverlayPositions(0, -deltaY)
            }
          }
        }
        overlayManager.startScrollTracking()
      }
    }
  }

  private fun startScreenshotLoop() {
    stopScreenshotLoop()
    screenshotRunnable = object : Runnable {
      override fun run() {
        var nextDelay = IDLE_SCAN_INTERVAL_MS
        try {
          syncForegroundPackage(null)
          nextDelay = if (System.currentTimeMillis() - lastTouchTime < TOUCH_ACTIVE_WINDOW_MS) {
            SCAN_INTERVAL_MS
          } else {
            IDLE_SCAN_INTERVAL_MS
          }
          if (powerManager.isInteractive && !isCapturing && shouldScanCurrentPackage()) {
            if (overlayManager.isShowing && !overlayManager.hasLiveNodes()) dismissActiveBlurOverlay()
            val activeOverlayRects = if (overlayManager.isShowing) {
              overlayManager.getTrackedRegionRects()
            } else emptyList()
            captureAndClassify(activeOverlayRects)
          }
        } catch (error: Exception) {
          Log.e(TAG, "Screenshot loop tick failed", error)
        } finally {
          if (screenshotRunnable === this) handler.postDelayed(this, nextDelay)
        }
      }
    }
    handler.post(checkNotNull(screenshotRunnable))
  }

  private fun stopScreenshotLoop() {
    screenshotRunnable?.let(handler::removeCallbacks)
    screenshotRunnable = null
    activeCaptureId = 0L
    isCapturing = false
  }

  private fun beginCapture(): Long {
    val captureId = ++nextCaptureId
    activeCaptureId = captureId
    isCapturing = true
    handler.postDelayed({
      if (isCapturing && activeCaptureId == captureId) {
        activeCaptureId = 0L
        isCapturing = false
      }
    }, CAPTURE_WATCHDOG_TIMEOUT_MS)
    return captureId
  }

  private fun isActiveCapture(captureId: Long) = isCapturing && activeCaptureId == captureId

  private fun finishCapture(captureId: Long): Boolean {
    if (activeCaptureId != captureId) return false
    activeCaptureId = 0L
    isCapturing = false
    return true
  }

  private data class OverlayRegionSplit(
    val overlayRegions: List<Pair<Rect, AccessibilityNodeInfo>>,
    val candidateRegions: List<Pair<Rect, AccessibilityNodeInfo>>,
  )

  private fun captureAndClassify(activeOverlayRects: List<Rect> = emptyList()) {
    val captureId = beginCapture()
    try {
      service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
        override fun onSuccess(screenshot: ScreenshotResult) {
          if (!isActiveCapture(captureId)) {
            screenshot.hardwareBuffer.close()
            return
          }
          val hardwareBitmap = try {
            Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
          } catch (_: Exception) {
            null
          } finally {
            screenshot.hardwareBuffer.close()
          }
          val softwareBitmap = try {
            hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
          } finally {
            hardwareBitmap?.recycle()
          }
          if (softwareBitmap == null) {
            finishCapture(captureId)
            return
          }
          scope.launch(Dispatchers.IO) {
            var regionsWithNodes: List<Pair<Rect, AccessibilityNodeInfo>> = emptyList()
            try {
              if (!isActiveCapture(captureId)) {
                softwareBitmap.recycle()
                return@launch
              }
              regionsWithNodes = withContext(Dispatchers.Main) { findImageRegionsWithNodes() }
              val split = splitRegionsForOverlayRefresh(regionsWithNodes, activeOverlayRects)
              val revalidateExistingOverlay = activeOverlayRects.isNotEmpty() &&
                split.candidateRegions.isEmpty() && split.overlayRegions.isNotEmpty()
              val regionsToClassify = if (revalidateExistingOverlay) split.overlayRegions else split.candidateRegions
              if (activeOverlayRects.isNotEmpty() && split.overlayRegions.isNotEmpty()) {
                overlayManager.refreshNodes(split.overlayRegions)
              }
              if (regionsToClassify.isNotEmpty()) {
                classifyRegions(softwareBitmap, regionsToClassify, activeOverlayRects.isNotEmpty())
              } else {
                softwareBitmap.recycle()
              }
            } catch (error: Exception) {
              Log.e(TAG, "Classification failed", error)
              softwareBitmap.recycle()
              regionsWithNodes.forEach { it.second.recycle() }
            } finally {
              finishCapture(captureId)
            }
          }
        }

        override fun onFailure(errorCode: Int) {
          finishCapture(captureId)
          Log.w(TAG, "Screenshot failed: $errorCode")
        }
      })
    } catch (error: Exception) {
      finishCapture(captureId)
      Log.e(TAG, "Failed to request screenshot", error)
    }
  }

  private suspend fun classifyRegions(
    screenshot: Bitmap,
    regionsWithNodes: List<Pair<Rect, AccessibilityNodeInfo>>,
    mergeIntoExistingOverlay: Boolean,
  ): Boolean {
    val nsfwRegions = mutableListOf<Pair<Rect, AccessibilityNodeInfo>>()
    val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()
    var worstConfidence = 0f
    val policy = currentPolicy() ?: run {
      screenshot.recycle()
      regionsWithNodes.forEach { it.second.recycle() }
      return false
    }
    val activeThreshold = activeConfidenceThreshold(policy.activeScreenSafety.nsfwScreen.sensitivity)
    val selection = selectRegionsForScan(regionsWithNodes)
    val regionsToCheck = selection.regionsToCheck
    nodesToRecycle.addAll(selection.skippedNodes)
    var processedCount = 0

    for ((rect, node) in regionsToCheck) {
      val x = rect.left.coerceIn(0, screenshot.width - 1)
      val y = rect.top.coerceIn(0, screenshot.height - 1)
      val width = rect.width().coerceAtMost(screenshot.width - x)
      val height = rect.height().coerceAtMost(screenshot.height - y)
      if (width <= 0 || height <= 0) {
        nodesToRecycle.add(node)
        continue
      }
      val crop = Bitmap.createBitmap(screenshot, x, y, width, height)
      val prediction = try {
        ChildSafetyModels.classifyNsfw(crop, policy.activeScreenSafety.nsfwScreen.sensitivity)
      } finally {
        crop.recycle()
      }
      processedCount += 1
      Log.d(TAG, "[SCAN] $rect: nsfw=${(prediction.confidence * 100).toInt()}% threshold=${(activeThreshold * 100).toInt()}%")
      if (prediction.confidence >= activeThreshold) {
        nsfwRegions.add(rect to node)
        worstConfidence = maxOf(worstConfidence, prediction.confidence)
        if (prediction.confidence > .9f) break
      } else {
        nodesToRecycle.add(node)
      }
    }
    if (processedCount < regionsToCheck.size) {
      for (index in processedCount until regionsToCheck.size) nodesToRecycle.add(regionsToCheck[index].second)
    }
    nodesToRecycle.forEach { it.recycle() }

    if (nsfwRegions.isNotEmpty()) {
      overlaySourcePackage = currentForegroundPackage
      if (mergeIntoExistingOverlay && overlayManager.isShowing) {
        overlayManager.mergeBlur(screenshot, nsfwRegions)
      } else {
        overlayManager.showBlur(screenshot, nsfwRegions)
      }
      if (incidentMemory.shouldCreate("nsfw_screen", currentForegroundPackage, screenFingerprint(screenshot))) {
        reporter.report(ChildSafetyAlert(
          incidentId = UUID.randomUUID().toString(),
          type = "nsfw_screen",
          packageName = currentForegroundPackage,
          confidenceBand = when {
            worstConfidence >= .8f -> "high"
            worstConfidence >= .55f -> "medium"
            else -> "low"
          },
          policyVersion = policy.version,
          occurredAt = System.currentTimeMillis(),
        ))
      }
    } else {
      screenshot.recycle()
    }
    return nsfwRegions.isNotEmpty()
  }

  private data class RegionSelection(
    val regionsToCheck: List<Pair<Rect, AccessibilityNodeInfo>>,
    val skippedNodes: List<AccessibilityNodeInfo>,
    val startIndex: Int,
  )

  private fun splitRegionsForOverlayRefresh(
    regionsWithNodes: List<Pair<Rect, AccessibilityNodeInfo>>,
    activeOverlayRects: List<Rect>,
  ): OverlayRegionSplit {
    if (activeOverlayRects.isEmpty()) return OverlayRegionSplit(emptyList(), regionsWithNodes)
    val overlayRegions = mutableListOf<Pair<Rect, AccessibilityNodeInfo>>()
    val candidateRegions = mutableListOf<Pair<Rect, AccessibilityNodeInfo>>()
    for (region in regionsWithNodes) {
      if (activeOverlayRects.any { regionsSignificantlyOverlap(region.first, it) }) overlayRegions.add(region)
      else candidateRegions.add(region)
    }
    return OverlayRegionSplit(overlayRegions, candidateRegions)
  }

  private fun regionsSignificantlyOverlap(first: Rect, second: Rect): Boolean {
    val intersection = Rect(first)
    if (!intersection.intersect(second)) return false
    val intersectionArea = intersection.width().toLong() * intersection.height().toLong()
    if (intersectionArea <= 0L) return false
    val firstArea = first.width().toLong() * first.height().toLong()
    val secondArea = second.width().toLong() * second.height().toLong()
    val smallerArea = minOf(firstArea, secondArea).coerceAtLeast(1L)
    val centerContained = second.contains(first.centerX(), first.centerY()) ||
      first.contains(second.centerX(), second.centerY())
    return centerContained || intersectionArea * 2 >= smallerArea
  }

  private fun selectRegionsForScan(regions: List<Pair<Rect, AccessibilityNodeInfo>>): RegionSelection {
    if (regions.isEmpty()) return RegionSelection(emptyList(), emptyList(), -1)
    val ordered = sortRegionsForScan(regions)
    val maxRegions = if (isActivelyScrolling()) ACTIVE_SCROLL_MAX_REGIONS_PER_SCAN else MAX_REGIONS_PER_SCAN
    if (ordered.size <= maxRegions) {
      regionScanStartIndex = 0
      return RegionSelection(ordered, emptyList(), -1)
    }
    val start = regionScanStartIndex % ordered.size
    val selectedPositions = (0 until maxRegions).map { (start + it) % ordered.size }.toHashSet()
    val selected = mutableListOf<Pair<Rect, AccessibilityNodeInfo>>()
    val skipped = mutableListOf<AccessibilityNodeInfo>()
    ordered.forEachIndexed { index, pair ->
      if (index in selectedPositions) selected.add(pair) else skipped.add(pair.second)
    }
    regionScanStartIndex = (start + maxRegions) % ordered.size
    return RegionSelection(selected, skipped, start)
  }

  private fun findImageRegionsWithNodes(): List<Pair<Rect, AccessibilityNodeInfo>> = try {
    val minSize = (MIN_IMAGE_SIZE_DP * service.resources.displayMetrics.density).toInt()
    val now = System.currentTimeMillis()
    val cachedRegions = if (shouldUseCachedImageFastPath(now)) collectRegionsFromCachedNodes(minSize, now) else emptyList()
    val fullScanDue = now - lastFullRegionScanTimeMs >= currentRegionRefreshIntervalMs()
    if (cachedRegions.isNotEmpty() && !fullScanDue) return cachedRegions
    val fullScanRegions = performFullImageRegionScan(minSize)
    if (fullScanRegions.isNotEmpty()) {
      updateCachedImageNodes(fullScanRegions, now)
      fullScanRegions
    } else cachedRegions
  } catch (error: Exception) {
    Log.w(TAG, "Failed to find image regions", error)
    emptyList()
  }

  private fun shouldUseCachedImageFastPath(now: Long) =
    currentForegroundPackage.isNotBlank() && cachedRegionPackageName == currentForegroundPackage &&
      cachedImageNodes.isNotEmpty() && isActivelyScrolling(now)

  private fun currentRegionRefreshIntervalMs() =
    if (isActivelyScrolling()) ACTIVE_SCROLL_CACHE_REFRESH_MS else IMAGE_NODE_CACHE_REFRESH_MS

  private fun isActivelyScrolling(now: Long = System.currentTimeMillis()) =
    now - lastScrollEventTimeMs <= SCROLL_PRELOAD_WINDOW_MS

  private fun collectRegionsFromCachedNodes(minSize: Int, now: Long): List<Pair<Rect, AccessibilityNodeInfo>> {
    val screenWidth = service.resources.displayMetrics.widthPixels
    val screenHeight = service.resources.displayMetrics.heightPixels
    val regions = mutableListOf<Pair<Rect, AccessibilityNodeInfo>>()
    val iterator = cachedImageNodes.iterator()
    while (iterator.hasNext()) {
      val cached = iterator.next()
      val node = cached.node
      if (!node.refresh()) {
        node.recycle(); iterator.remove(); continue
      }
      val rect = Rect().also(node::getBoundsInScreen)
      if (!intersectsViewport(rect, screenWidth, screenHeight)) {
        node.recycle(); iterator.remove(); continue
      }
      if (!((rect.width() >= minSize && rect.height() >= minSize) || isIncomingPartialRegion(rect, minSize))) continue
      cached.lastSeenMs = now
      regions.add(Rect(rect) to AccessibilityNodeInfo.obtain(node))
    }
    return regions
  }

  private fun performFullImageRegionScan(minSize: Int): List<Pair<Rect, AccessibilityNodeInfo>> {
    val regions = mutableListOf<Pair<Rect, AccessibilityNodeInfo>>()
    val activeRoot = service.rootInActiveWindow
    if (activeRoot != null) {
      collectImageBounds(activeRoot, regions, minSize)
      activeRoot.recycle()
      lastFullRegionScanTimeMs = System.currentTimeMillis()
      if (regions.isNotEmpty()) return regions
    }
    for (window in service.windows) {
      val root = window.root ?: continue
      collectImageBounds(root, regions, minSize)
      root.recycle()
    }
    lastFullRegionScanTimeMs = System.currentTimeMillis()
    return regions
  }

  private fun updateCachedImageNodes(regions: List<Pair<Rect, AccessibilityNodeInfo>>, now: Long) {
    clearCachedImageNodes()
    cachedRegionPackageName = currentForegroundPackage
    for ((_, node) in regions) cachedImageNodes.add(CachedImageNode(AccessibilityNodeInfo.obtain(node), now))
  }

  private fun clearCachedImageNodes() {
    cachedImageNodes.forEach { it.node.recycle() }
    cachedImageNodes.clear()
    cachedRegionPackageName = ""
  }

  private fun collectImageBounds(
    node: AccessibilityNodeInfo,
    regions: MutableList<Pair<Rect, AccessibilityNodeInfo>>,
    minSize: Int,
  ): Boolean {
    var kept = false
    val className = node.className?.toString() ?: ""
    val isImageType = className.contains("ImageView") || className.contains("DraweeView") ||
      className.contains("SurfaceView") || className.contains("TextureView") ||
      className.contains("Thumbnail") || className.contains("PhotoView")
    val isLikelyImage = !isImageType && node.childCount == 0 && node.contentDescription != null &&
      node.text == null && !className.contains("TextView") && !className.contains("EditText") &&
      !className.contains("Button")
    if (isImageType || isLikelyImage) {
      val rect = Rect().also(node::getBoundsInScreen)
      if ((rect.width() >= minSize && rect.height() >= minSize) || isIncomingPartialRegion(rect, minSize)) {
        regions.add(rect to node)
        kept = true
      }
    }
    for (index in 0 until node.childCount) {
      val child = node.getChild(index) ?: continue
      if (!collectImageBounds(child, regions, minSize)) child.recycle()
    }
    return kept
  }

  private fun sortRegionsForScan(regions: List<Pair<Rect, AccessibilityNodeInfo>>): List<Pair<Rect, AccessibilityNodeInfo>> {
    val direction = recentScrollDirection() ?: 0
    return regions.sortedWith { left, right ->
      val priority = incomingPriority(left.first).compareTo(incomingPriority(right.first))
      if (priority != 0) priority
      else if (direction > 0) compareValuesBy(left, right, { -it.first.bottom }, { -it.first.top }, { it.first.left })
      else compareValuesBy(left, right, { it.first.top }, { it.first.left }, { it.first.bottom })
    }
  }

  private fun incomingPriority(rect: Rect) = if (recentScrollDirection()?.let { isIncomingEdgeRegion(rect, it) } == true) 0 else 1

  private fun isIncomingPartialRegion(rect: Rect, minSize: Int): Boolean {
    val direction = recentScrollDirection() ?: return false
    val minVisibleHeight = (INCOMING_MIN_VISIBLE_DP * service.resources.displayMetrics.density).toInt()
    return isIncomingEdgeRegion(rect, direction) && rect.width() >= minSize && rect.height() >= minVisibleHeight
  }

  private fun isIncomingEdgeRegion(rect: Rect, direction: Int): Boolean {
    val screenHeight = service.resources.displayMetrics.heightPixels
    val edgeBand = (INCOMING_EDGE_BAND_DP * service.resources.displayMetrics.density).toInt()
    return when {
      direction > 0 -> rect.bottom >= screenHeight - edgeBand
      direction < 0 -> rect.top <= edgeBand
      else -> false
    }
  }

  private fun recentScrollDirection(): Int? {
    if (System.currentTimeMillis() - lastScrollEventTimeMs > SCROLL_PRELOAD_WINDOW_MS) return null
    return when {
      lastScrollDeltaY > 0 -> 1
      lastScrollDeltaY < 0 -> -1
      else -> null
    }
  }

  private fun syncForegroundPackage(event: AccessibilityEvent?) {
    val policy = currentPolicy() ?: return
    val monitored = policy.activeScreenSafety.nsfwScreen.monitoredPackageNames
    val candidate = event?.packageName?.toString().orEmpty()
    val active = service.rootInActiveWindow?.let { root ->
      try { root.packageName?.toString().orEmpty() } finally { root.recycle() }
    }.orEmpty()
    val resolved = when {
      active in monitored -> active
      candidate in monitored -> candidate
      else -> active.ifBlank { candidate }
    }
    if (resolved.isNotBlank() && resolved != currentForegroundPackage) {
      clearCachedImageNodes()
      currentForegroundPackage = resolved
      if (overlaySourcePackage.isNotEmpty() && resolved != overlaySourcePackage) dismissActiveBlurOverlay()
    }
  }

  private fun shouldScanCurrentPackage(): Boolean {
    val policy = currentPolicy() ?: return false
    if (!policy.activeScreenSafety.nsfwScreen.enabled ||
      currentForegroundPackage !in policy.activeScreenSafety.nsfwScreen.monitoredPackageNames
    ) return false
    return !isManualBlurSnoozed()
  }

  private fun activeConfidenceThreshold(sensitivity: SafetySensitivity) = when (sensitivity) {
    SafetySensitivity.Lower -> .60f
    SafetySensitivity.Standard -> .40f
    SafetySensitivity.Higher -> .10f
  }

  private fun handleManualBlurDismiss() {
    manualBlurSnoozePackage = currentForegroundPackage.ifBlank { overlaySourcePackage }
    manualBlurSnoozeUntilMs = System.currentTimeMillis() + MANUAL_BLUR_SNOOZE_MS
    dismissActiveBlurOverlay()
  }

  private fun isManualBlurSnoozed(): Boolean {
    if (currentForegroundPackage != manualBlurSnoozePackage) return false
    if (System.currentTimeMillis() >= manualBlurSnoozeUntilMs) {
      manualBlurSnoozePackage = ""
      manualBlurSnoozeUntilMs = 0L
      return false
    }
    return true
  }

  private fun dismissActiveBlurOverlay() {
    overlayManager.dismiss()
    overlaySourcePackage = ""
  }

  private fun currentPolicy(): ChildSupervisionPolicy? = service
    .getSharedPreferences("child_policy_runtime", AccessibilityService.MODE_PRIVATE)
    .getString("policy", null)
    ?.let { runCatching { ChildSupervisionPolicy.parse(it) }.getOrNull() }

  private fun screenFingerprint(bitmap: Bitmap): String {
    val sample = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
    val pixels = IntArray(64)
    sample.getPixels(pixels, 0, 8, 0, 0, 8, 8)
    sample.recycle()
    val luminance = pixels.map { Color.red(it) * 3 + Color.green(it) * 6 + Color.blue(it) }
    val average = luminance.average()
    return luminance.joinToString("") { if (it >= average) "1" else "0" }
  }

  private fun intersectsViewport(rect: Rect, width: Int, height: Int) =
    rect.right > 0 && rect.bottom > 0 && rect.left < width && rect.top < height

  fun clear() {
    stopScreenshotLoop()
    overlayManager.dismiss()
    clearCachedImageNodes()
    incidentMemory.clear("nsfw_screen")
  }

  private companion object {
    const val TAG = "ScreenScanService"
    const val MIN_IMAGE_SIZE_DP = 100
    const val MAX_REGIONS_PER_SCAN = 24
    const val SCROLL_PRELOAD_WINDOW_MS = 900L
    const val INCOMING_EDGE_BAND_DP = 180
    const val INCOMING_MIN_VISIBLE_DP = 36
    const val IMAGE_NODE_CACHE_REFRESH_MS = 450L
    const val ACTIVE_SCROLL_CACHE_REFRESH_MS = 900L
    const val ACTIVE_SCROLL_MAX_REGIONS_PER_SCAN = 1
    const val OVERLAY_DISMISS_SCROLL_FRACTION = .30f
    const val CAPTURE_WATCHDOG_TIMEOUT_MS = 30_000L
    const val MANUAL_BLUR_SNOOZE_MS = 3_000L
    const val TOUCH_ACTIVE_WINDOW_MS = 2_000L
    const val SCAN_INTERVAL_MS = 200L
    const val IDLE_SCAN_INTERVAL_MS = 1_000L
  }
}
