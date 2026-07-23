package com.joshua.circletoshare.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import com.joshua.circletoshare.R
import com.joshua.circletoshare.crop.CropScreen
import com.joshua.circletoshare.share.ShareHelper
import kotlin.math.abs

/**
 * Two jobs:
 *  1. Fallback screen capture for the assistant session (via [capture]).
 *  2. Optional floating bubble that captures + shows the crop overlay from
 *     anywhere, independent of the launcher's gesture. Toggled from the app.
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ScreenshotAccessibilityService? = null
            private set

        private const val PREFS = "cts"
        private const val KEY_BUBBLE = "bubble_enabled"

        fun isBubbleEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BUBBLE, false)

        fun setBubblePref(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BUBBLE, enabled).apply()
            instance?.applyBubbleState(enabled)
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var overlayView: CropScreen? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        if (isBubbleEnabled(this)) applyBubbleState(true)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardownOverlay()
        removeBubble()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardownOverlay()
        removeBubble()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // --- Fallback capture used by the assistant session ---------------------

    fun capture(callback: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardware = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer, screenshot.colorSpace
                    )
                    val result = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    callback(result)
                }

                override fun onFailure(errorCode: Int) = callback(null)
            }
        )
    }

    // --- Floating bubble ----------------------------------------------------

    fun applyBubbleState(enabled: Boolean) {
        ui.post { if (enabled) addBubble() else removeBubble() }
    }

    private fun addBubble() {
        if (bubbleView != null) return
        val size = dp(52)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble)
            val pad = dp(13)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 26, 28, 44))
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(8)
            y = resources.displayMetrics.heightPixels / 3
        }
        attachBubbleTouch(icon, params)
        windowManager.addView(icon, params)
        bubbleView = icon
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    private fun attachBubbleTouch(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    originX = params.x; originY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX)
                    val dy = (event.rawY - startY)
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    params.x = originX + dx.toInt()
                    params.y = originY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { v.performClick(); triggerCapture() }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Hide the bubble (so it isn't in the shot) too, then grab the screen and
     * show the crop overlay. Shared by the floating bubble and Android's native
     * accessibility button.
     */
    fun triggerCapture() {
        if (overlayView != null) return // already capturing
        removeBubble()
        ui.postDelayed({
            capture { bitmap ->
                ui.post {
                    if (bitmap != null) presentOverlay(bitmap) else {
                        Toast.makeText(
                            this, R.string.error_capture_failed, Toast.LENGTH_LONG
                        ).show()
                        restoreBubble()
                    }
                }
            }
        }, 80)
    }

    /**
     * Called by the assistant session so the bubble doesn't linger over the
     * session's own overlay. Hides while [hidden], restores after (if enabled).
     */
    fun setBubbleHidden(hidden: Boolean) {
        ui.post {
            if (hidden) removeBubble()
            else if (overlayView == null) restoreBubble()
        }
    }

    private fun presentOverlay(bitmap: Bitmap) {
        ShareHelper.cleanupCache(this)
        teardownOverlay()

        val screen = CropScreen(this)
        screen.isFocusableInTouchMode = true
        screen.callbacks = object : CropScreen.Callbacks {
            override fun onDeliver(share: Boolean, rect: Rect?) {
                val ok = try {
                    ShareHelper.deliver(this@ScreenshotAccessibilityService, bitmap, rect, share)
                } catch (e: Exception) {
                    Toast.makeText(
                        this@ScreenshotAccessibilityService,
                        R.string.error_capture_failed, Toast.LENGTH_LONG
                    ).show()
                    true
                }
                if (ok) dismissOverlay()
            }

            override fun onCancel() = dismissOverlay()
        }
        // Cancel on the back key.
        screen.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissOverlay(); true
            } else false
        }

        // Size to the whole physical display so the dim covers the status and
        // navigation bars too — not just the app content area.
        val bounds = windowManager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        windowManager.addView(screen, params)
        overlayView = screen
        screen.setBitmap(bitmap)
        screen.requestFocus()
    }

    private fun dismissOverlay() {
        teardownOverlay()
        restoreBubble()
    }

    private fun teardownOverlay() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
    }

    private fun restoreBubble() {
        if (isBubbleEnabled(this)) addBubble()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
