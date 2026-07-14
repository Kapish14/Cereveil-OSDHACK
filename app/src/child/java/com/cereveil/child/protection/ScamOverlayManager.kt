package com.cereveil.child.protection

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class ScamOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var currentOverlay: View? = null
    private var autoDismissRunnable: Runnable? = null

    fun showWarning() {
        handler.post {
            // Inline cleanup of previous overlay (NOT calling dismiss() which would post another runnable)
            removeCurrentOverlay()

            val overlay = buildOverlayView()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            try {
                windowManager.addView(overlay, params)
                currentOverlay = overlay
                android.util.Log.d("ScamOverlayManager", "Overlay added to WindowManager")
                autoDismissRunnable = Runnable { removeCurrentOverlay() }
                handler.postDelayed(autoDismissRunnable!!, 15000)
            } catch (e: Exception) {
                android.util.Log.e("ScamOverlayManager", "Failed to show overlay", e)
            }
        }
    }

    fun dismiss() {
        handler.post { removeCurrentOverlay() }
    }

    private fun removeCurrentOverlay() {
        autoDismissRunnable?.let { handler.removeCallbacks(it) }
        autoDismissRunnable = null
        currentOverlay?.let { overlay ->
            try {
                windowManager.removeView(overlay)
                android.util.Log.d("ScamOverlayManager", "Overlay removed")
            } catch (_: Exception) {}
        }
        currentOverlay = null
    }

    private fun buildOverlayView(): View {
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt() }

        // Outer container with padding
        val root = FrameLayout(context).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Center content column — dark card with red border and rounded corners
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0111111"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(3), Color.parseColor("#FFFF1744"))
            }
            elevation = dp(16).toFloat()
        }

        // Warning icon circle
        val iconCircle = FrameLayout(context).apply {
            val size = dp(72)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFD50000"))
            }
        }
        val warningIcon = TextView(context).apply {
            text = "\u26A0"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        iconCircle.addView(warningIcon)
        content.addView(iconCircle)

        // "SCAM DETECTED" header
        content.addView(TextView(context).apply {
            text = "SCAM DETECTED"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(Color.parseColor("#FFFF1744"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        })

        // Keep the warning intentionally generic. Do not repeat private message content or expose
        // model internals such as the predicted class and confidence score.
        content.addView(TextView(context).apply {
            text = "This message may be trying to steal personal or financial information."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        })

        // Warning text
        content.addView(TextView(context).apply {
            text = "Do NOT share any personal information,\nOTPs, or bank details!"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#FFFF8A80"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(32) }
        })

        // Dismiss button
        val dismissBtn = Button(context).apply {
            text = "I Understand \u2014 Dismiss"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFD50000"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
            setOnClickListener { removeCurrentOverlay() }
        }
        content.addView(dismissBtn)

        val contentParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }

        root.addView(content, contentParams)
        return root
    }
}
