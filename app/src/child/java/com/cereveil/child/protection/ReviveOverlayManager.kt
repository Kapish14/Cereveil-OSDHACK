package com.cereveil.child.protection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

private const val TAG = "OverlayManager"
private const val SCROLL_ACTIVE_WINDOW_MS = 1_200L
private const val NODE_STALE_GRACE_MS = 1_500L
private const val MIN_BLUR_HOLD_MS = 2_500L
private const val WARNING_PROMPT_DURATION_MS = 2_200L

class OverlayManager(
    private val context: Context,
    private val shouldShowFalsePositiveAction: () -> Boolean = { true },
    private val onFalsePositiveDismiss: (() -> Unit)? = null
) {

    private data class TrackedOverlay(
        val view: View,
        val imageView: ImageView?,
        val originalRect: Rect,
        var currentRect: Rect,
        var node: AccessibilityNodeInfo?,
        var lastLiveTimeMs: Long
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()

    private var isPolling = false
    private var lastScrollEventTimeMs = 0L
    private var lastScrollDeltaY = 0

    private val positionPollingCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isShowing || isFullScreenBlur) {
                isPolling = false
                return
            }

            val isActivelyScrolling = System.currentTimeMillis() - lastScrollEventTimeMs < SCROLL_ACTIVE_WINDOW_MS
            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels
            val toRemove = mutableListOf<TrackedOverlay>()
            var anyRefreshed = false
            var anyMoved = false

            for (tracked in blurOverlays) {
                val node = tracked.node
                if (node != null) {
                    if (node.refresh()) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (!intersectsViewport(bounds, screenW, screenH)) {
                            node.recycle()
                            tracked.node = null
                            toRemove.add(tracked)
                            continue
                        }

                        val fullRect = inferFullRect(tracked, bounds, screenW, screenH)
                        val previousRect = Rect(tracked.currentRect)
                        tracked.lastLiveTimeMs = System.currentTimeMillis()

                        if (updateOverlayLayout(tracked, fullRect, screenW, screenH)) {
                            anyRefreshed = true
                            if (tracked.currentRect != previousRect) {
                                anyMoved = true
                            }
                        } else {
                            toRemove.add(tracked)
                        }
                    } else {
                        node.recycle()
                        tracked.node = null
                        val offscreen = tracked.currentRect.right <= 0 ||
                                tracked.currentRect.bottom <= 0 ||
                                tracked.currentRect.left >= screenW ||
                                tracked.currentRect.top >= screenH
                        if (!offscreen && isWithinMinimumHoldWindow()) {
                            continue
                        }
                        if ((!isActivelyScrolling && !isWithinGracePeriod(tracked)) || offscreen) {
                            toRemove.add(tracked)
                        }
                    }
                } else {
                    val params = tracked.view.layoutParams as WindowManager.LayoutParams
                    val offscreen = params.x + params.width <= 0 ||
                            params.y + params.height <= 0 ||
                            params.x >= screenW ||
                            params.y >= screenH
                    if (!offscreen && isWithinMinimumHoldWindow()) {
                        continue
                    }
                    if ((!isActivelyScrolling && !isWithinGracePeriod(tracked)) || offscreen) {
                        toRemove.add(tracked)
                    }
                }
            }

            for (tracked in toRemove) {
                try { windowManager.removeView(tracked.view) } catch (_: Exception) {}
                tracked.node?.recycle()
                blurOverlays.remove(tracked)
            }

            if (blurOverlays.isEmpty()) {
                Log.d(TAG, "All blur overlays gone, dismissing")
                removeCurrentOverlay()
                isPolling = false
                return
            }

            if (anyRefreshed) {
                lastUpdateTimeMs = System.currentTimeMillis()
            }

            if (isActivelyScrolling || anyMoved) {
                choreographer.postFrameCallback(this)
            } else {
                isPolling = false
            }
        }
    }

    private val blurOverlays = mutableListOf<TrackedOverlay>()
    private var isFullScreenBlur = false
    private var actionView: View? = null
    private var warningPromptView: View? = null
    private var warningPromptDismissRunnable: Runnable? = null

    var isShowing: Boolean = false
        private set

    var lastUpdateTimeMs: Long = 0L
        private set

    var lastDismissTimeMs: Long = 0L
        private set

    fun offsetOverlayPositions(deltaX: Int, deltaY: Int) {
        if (isFullScreenBlur || !isShowing) return

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        lastScrollDeltaY = deltaY

        for (tracked in blurOverlays) {
            val shiftedFullRect = Rect(tracked.currentRect).apply {
                offset(deltaX, deltaY)
            }
            updateOverlayLayout(
                tracked = tracked,
                fullRect = shiftedFullRect,
                screenW = screenW,
                screenH = screenH,
                allowOffscreen = true
            )
        }
    }

    fun hasLiveNodes(): Boolean {
        if (isWithinMinimumHoldWindow()) {
            return true
        }
        if (isFullScreenBlur) {
            val elapsed = System.currentTimeMillis() - lastUpdateTimeMs
            return elapsed < 10_000L
        }
        var anyAlive = false
        val now = System.currentTimeMillis()
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        for (tracked in blurOverlays) {
            val node = tracked.node ?: continue
            if (node.refresh()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val isOnScreen = intersectsViewport(bounds, screenW, screenH)
                if (isOnScreen) {
                    anyAlive = true
                    tracked.lastLiveTimeMs = now
                } else {
                    node.recycle()
                    tracked.node = null
                }
            } else {
                node.recycle()
                tracked.node = null
            }
        }
        if (anyAlive) return true
        return blurOverlays.any { now - it.lastLiveTimeMs < NODE_STALE_GRACE_MS }
    }

    fun startScrollTracking() {
        lastScrollEventTimeMs = System.currentTimeMillis()
        if (!isPolling && isShowing && !isFullScreenBlur) {
            isPolling = true
            choreographer.postFrameCallback(positionPollingCallback)
        }
    }

    fun refreshNodes(newRegions: List<Pair<Rect, AccessibilityNodeInfo?>>) {
        handler.post {
            val available = newRegions.toMutableList()
            for (tracked in blurOverlays) {
                tracked.node?.recycle()
                tracked.node = null

                var bestIdx = -1
                var bestDist = Int.MAX_VALUE
                for ((idx, pair) in available.withIndex()) {
                    val dx = kotlin.math.abs(tracked.currentRect.centerX() - pair.first.centerX())
                    val dy = kotlin.math.abs(tracked.currentRect.centerY() - pair.first.centerY())
                    val dist = dx + dy
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIdx = idx
                    }
                }
                if (bestIdx >= 0) {
                    tracked.node = available[bestIdx].second
                    tracked.lastLiveTimeMs = System.currentTimeMillis()
                    available.removeAt(bestIdx)
                }
            }
            for ((_, node) in available) {
                node?.recycle()
            }
            lastUpdateTimeMs = System.currentTimeMillis()
        }
    }

    fun getTrackedRegionRects(): List<Rect> {
        return blurOverlays.map { Rect(it.currentRect) }
    }

    fun showBlur(
        screenshotBitmap: Bitmap,
        imageRegions: List<Pair<Rect, AccessibilityNodeInfo?>>
    ) {
        handler.post {
            removeCurrentOverlay()
            try {
                addBlurOverlays(screenshotBitmap, imageRegions)
                showFalsePositiveAction()
                isShowing = true
                lastUpdateTimeMs = System.currentTimeMillis()
                Log.d(TAG, "Blur overlay shown, regions=${imageRegions.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show overlay", e)
            }
        }
    }

    fun updateBlur(screenshotBitmap: Bitmap, imageRegions: List<Pair<Rect, AccessibilityNodeInfo?>>) {
        handler.post {
            removeBlurOverlays()
            try {
                addBlurOverlays(screenshotBitmap, imageRegions)
                lastUpdateTimeMs = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update blur", e)
            }
        }
    }

    fun mergeBlur(
        screenshotBitmap: Bitmap,
        imageRegions: List<Pair<Rect, AccessibilityNodeInfo?>>
    ) {
        handler.post {
            if (imageRegions.isEmpty()) return@post

            if (!isShowing || isFullScreenBlur) {
                try {
                    removeCurrentOverlay()
                    addBlurOverlays(screenshotBitmap, imageRegions)
                    showFalsePositiveAction()
                    isShowing = true
                    lastUpdateTimeMs = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to merge blur overlay", e)
                }
                return@post
            }

            try {
                val now = System.currentTimeMillis()
                showFalsePositiveAction()
                for ((rect, node) in imageRegions) {
                    val existing = findMatchingOverlay(rect)
                    if (existing != null) {
                        existing.node?.recycle()
                        existing.node = node
                        existing.lastLiveTimeMs = now
                    } else {
                        addRegionBlur(screenshotBitmap, rect, node)
                    }
                }
                isShowing = blurOverlays.isNotEmpty()
                lastUpdateTimeMs = now
            } catch (e: Exception) {
                Log.e(TAG, "Failed to merge blur overlay", e)
            }
        }
    }

    fun dismiss() {
        handler.post {
            lastDismissTimeMs = System.currentTimeMillis()
            removeCurrentOverlay()
        }
    }

    fun showWarningPrompt(message: String) {
        handler.post {
            removeWarningPrompt()

            val horizontalPadding = context.dpToPx(14)
            val verticalPadding = context.dpToPx(10)
            val iconSize = context.dpToPx(28)
            val topMargin = context.dpToPx(28)

            val pillBackground = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dpToPx(24).toFloat()
                setColor(Color.parseColor("#F8F5F1"))
                setStroke(context.dpToPx(1), Color.parseColor("#22000000"))
            }
            val iconBackground = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
            }

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                background = pillBackground
                elevation = context.dpToPx(8).toFloat()
            }

            val icon = TextView(context).apply {
                text = "!"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = iconBackground
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }

            val label = TextView(context).apply {
                text = message
                setTextColor(Color.parseColor("#171717"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 1
                typeface = android.graphics.Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = context.dpToPx(12)
                }
            }

            root.addView(icon)
            root.addView(label)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = topMargin
            }

            try {
                windowManager.addView(root, params)
                warningPromptView = root
                warningPromptDismissRunnable = Runnable { removeWarningPrompt() }.also {
                    handler.postDelayed(it, WARNING_PROMPT_DURATION_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show warning prompt", e)
            }
        }
    }

    // -- Blur overlays --

    private fun addBlurOverlays(screenshot: Bitmap, regions: List<Pair<Rect, AccessibilityNodeInfo?>>) {
        if (regions.isEmpty()) {
            isFullScreenBlur = true
            addFullScreenBlur(screenshot)
        } else {
            isFullScreenBlur = false
            for ((rect, node) in regions) {
                addRegionBlur(screenshot, rect, node)
            }
        }
    }

    private fun addFullScreenBlur(screenshot: Bitmap) {
        val blurred = blurBitmap(screenshot)
        val root = FrameLayout(context)

        root.addView(ImageView(context).apply {
            setImageBitmap(blurred)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })

        root.addView(View(context).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(root, params)
        val sentinel = Rect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)
        blurOverlays.add(TrackedOverlay(root, null, sentinel, sentinel, null, System.currentTimeMillis()))
    }

    private fun addRegionBlur(screenshot: Bitmap, rect: Rect, node: AccessibilityNodeInfo?) {
        val srcW = screenshot.width
        val srcH = screenshot.height
        val x = rect.left.coerceIn(0, srcW - 1)
        val y = rect.top.coerceIn(0, srcH - 1)
        val w = rect.width().coerceAtMost(srcW - x)
        val h = rect.height().coerceAtMost(srcH - y)
        if (w <= 0 || h <= 0) return

        val cropped = Bitmap.createBitmap(screenshot, x, y, w, h)
        val blurred = blurBitmap(cropped)

        val imageView = ImageView(context).apply {
            setImageBitmap(blurred)
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height())
        }
        val container = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
            addView(imageView)
        }

        val params = WindowManager.LayoutParams(
            rect.width(),
            rect.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = rect.left
            this.y = rect.top
        }

        windowManager.addView(container, params)
        blurOverlays.add(
            TrackedOverlay(
                view = container,
                imageView = imageView,
                originalRect = Rect(rect),
                currentRect = Rect(rect),
                node = node,
                lastLiveTimeMs = System.currentTimeMillis()
            )
        )
    }

    private fun updateOverlayLayout(
        tracked: TrackedOverlay,
        fullRect: Rect,
        screenW: Int,
        screenH: Int,
        allowOffscreen: Boolean = false
    ): Boolean {
        if (!intersectsViewport(fullRect, screenW, screenH)) {
            tracked.currentRect.set(fullRect)
            if (!allowOffscreen) return false
            return try {
                tracked.view.visibility = View.INVISIBLE
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hide off-screen overlay", e)
                false
            }
        }

        return try {
            val params = tracked.view.layoutParams as WindowManager.LayoutParams
            params.x = fullRect.left
            params.y = fullRect.top
            params.width = fullRect.width()
            params.height = fullRect.height()

            tracked.imageView?.let { imageView ->
                val childParams = imageView.layoutParams as FrameLayout.LayoutParams
                if (childParams.width != fullRect.width() || childParams.height != fullRect.height()) {
                    childParams.width = fullRect.width()
                    childParams.height = fullRect.height()
                    imageView.layoutParams = childParams
                }
                imageView.translationX = 0f
                imageView.translationY = 0f
            }

            tracked.view.visibility = View.VISIBLE
            windowManager.updateViewLayout(tracked.view, params)
            tracked.currentRect.set(fullRect)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update overlay layout", e)
            false
        }
    }

    private fun inferFullRect(
        tracked: TrackedOverlay,
        visibleBounds: Rect,
        screenW: Int,
        screenH: Int
    ): Rect {
        val inferred = Rect(visibleBounds)
        val targetWidth = maxOf(
            tracked.originalRect.width(),
            tracked.currentRect.width(),
            visibleBounds.width()
        )
        val targetHeight = maxOf(
            tracked.originalRect.height(),
            tracked.currentRect.height(),
            visibleBounds.height()
        )

        if (visibleBounds.width() < targetWidth) {
            val leftDelta = kotlin.math.abs(visibleBounds.left - tracked.currentRect.left)
            val rightDelta = kotlin.math.abs(visibleBounds.right - tracked.currentRect.right)
            when {
                rightDelta <= leftDelta -> {
                    inferred.right = visibleBounds.right
                    inferred.left = inferred.right - targetWidth
                }
                leftDelta < rightDelta -> {
                    inferred.left = visibleBounds.left
                    inferred.right = inferred.left + targetWidth
                }
                visibleBounds.left <= 0 && visibleBounds.right >= screenW -> {
                    inferred.left = tracked.currentRect.left
                    inferred.right = inferred.left + targetWidth
                }
            }
        }

        if (visibleBounds.height() < targetHeight) {
            val isRecentScroll = System.currentTimeMillis() - lastScrollEventTimeMs < SCROLL_ACTIVE_WINDOW_MS
            val topDelta = kotlin.math.abs(visibleBounds.top - tracked.currentRect.top)
            val bottomDelta = kotlin.math.abs(visibleBounds.bottom - tracked.currentRect.bottom)
            when {
                isRecentScroll && lastScrollDeltaY < 0 -> {
                    inferred.bottom = visibleBounds.bottom
                    inferred.top = inferred.bottom - targetHeight
                }
                isRecentScroll && lastScrollDeltaY > 0 -> {
                    inferred.top = visibleBounds.top
                    inferred.bottom = inferred.top + targetHeight
                }
                bottomDelta <= topDelta -> {
                    inferred.bottom = visibleBounds.bottom
                    inferred.top = inferred.bottom - targetHeight
                }
                topDelta < bottomDelta -> {
                    inferred.top = visibleBounds.top
                    inferred.bottom = inferred.top + targetHeight
                }
                visibleBounds.top <= 0 && visibleBounds.bottom >= screenH -> {
                    inferred.top = tracked.currentRect.top
                    inferred.bottom = inferred.top + targetHeight
                }
            }
        }

        return inferred
    }

    private fun intersectsViewport(rect: Rect, screenW: Int, screenH: Int): Boolean {
        return rect.right > 0 &&
                rect.bottom > 0 &&
                rect.left < screenW &&
                rect.top < screenH
    }

    private fun isWithinGracePeriod(tracked: TrackedOverlay): Boolean {
        return System.currentTimeMillis() - tracked.lastLiveTimeMs < NODE_STALE_GRACE_MS
    }

    private fun isWithinMinimumHoldWindow(): Boolean {
        return isShowing && System.currentTimeMillis() - lastUpdateTimeMs < MIN_BLUR_HOLD_MS
    }

    private fun findMatchingOverlay(rect: Rect): TrackedOverlay? {
        return blurOverlays.firstOrNull { regionsSignificantlyOverlap(rect, it.currentRect) }
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

    private fun blurBitmap(source: Bitmap): Bitmap {
        val scale = 0.2f
        val sw = (source.width * scale).toInt().coerceAtLeast(1)
        val sh = (source.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, sw, sh, true)
            .copy(Bitmap.Config.ARGB_8888, true)

        val w = small.width
        val h = small.height
        val src = IntArray(w * h)
        small.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(w * h)

        val r = 15
        repeat(3) {
            for (y in 0 until h) {
                val pR = IntArray(w + 1); val pG = IntArray(w + 1); val pB = IntArray(w + 1)
                for (x in 0 until w) {
                    val c = src[y * w + x]
                    pR[x + 1] = pR[x] + ((c shr 16) and 0xFF)
                    pG[x + 1] = pG[x] + ((c shr 8) and 0xFF)
                    pB[x + 1] = pB[x] + (c and 0xFF)
                }
                for (x in 0 until w) {
                    val lo = (x - r).coerceAtLeast(0)
                    val hi = (x + r).coerceAtMost(w - 1)
                    val n = hi - lo + 1
                    dst[y * w + x] = (0xFF shl 24) or
                            (((pR[hi + 1] - pR[lo]) / n) shl 16) or
                            (((pG[hi + 1] - pG[lo]) / n) shl 8) or
                            ((pB[hi + 1] - pB[lo]) / n)
                }
            }
            for (x in 0 until w) {
                val pR = IntArray(h + 1); val pG = IntArray(h + 1); val pB = IntArray(h + 1)
                for (y in 0 until h) {
                    val c = dst[y * w + x]
                    pR[y + 1] = pR[y] + ((c shr 16) and 0xFF)
                    pG[y + 1] = pG[y] + ((c shr 8) and 0xFF)
                    pB[y + 1] = pB[y] + (c and 0xFF)
                }
                for (y in 0 until h) {
                    val lo = (y - r).coerceAtLeast(0)
                    val hi = (y + r).coerceAtMost(h - 1)
                    val n = hi - lo + 1
                    src[y * w + x] = (0xFF shl 24) or
                            (((pR[hi + 1] - pR[lo]) / n) shl 16) or
                            (((pG[hi + 1] - pG[lo]) / n) shl 8) or
                            ((pB[hi + 1] - pB[lo]) / n)
                }
            }
        }

        small.setPixels(src, 0, w, 0, 0, w, h)
        return Bitmap.createScaledBitmap(small, source.width, source.height, true)
    }

    // -- Cleanup --

    private fun removeBlurOverlays() {
        for (tracked in blurOverlays) {
            try { windowManager.removeView(tracked.view) } catch (_: Exception) {}
            tracked.node?.recycle()
        }
        blurOverlays.clear()
        isFullScreenBlur = false
    }

    private fun removeCurrentOverlay() {
        choreographer.removeFrameCallback(positionPollingCallback)
        isPolling = false
        removeActionView()
        removeWarningPrompt()
        removeBlurOverlays()
        isShowing = false
    }

    private fun showFalsePositiveAction() {
        if (onFalsePositiveDismiss == null || actionView != null) return
        if (!shouldShowFalsePositiveAction()) {
            removeActionView()
            return
        }

        val margin = context.dpToPx(16)
        val horizontalPadding = context.dpToPx(14)
        val verticalPadding = context.dpToPx(10)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dpToPx(18).toFloat()
            setColor(Color.parseColor("#E61A1A1A"))
            setStroke(context.dpToPx(1), Color.parseColor("#33FFFFFF"))
        }

        val chip = TextView(context).apply {
            text = "Hide for 3s"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            setBackground(background)
            isClickable = true
            isFocusable = false
            setOnClickListener { onFalsePositiveDismiss.invoke() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = margin
            y = margin
        }

        windowManager.addView(chip, params)
        actionView = chip
    }

    private fun removeActionView() {
        val view = actionView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        actionView = null
    }

    private fun removeWarningPrompt() {
        warningPromptDismissRunnable?.let { handler.removeCallbacks(it) }
        warningPromptDismissRunnable = null
        val view = warningPromptView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        warningPromptView = null
    }
}

private fun Context.dpToPx(value: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()
}
