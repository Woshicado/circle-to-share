package com.joshua.circletoshare.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val UNSET = Int.MIN_VALUE

        fun isBubbleEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BUBBLE, false)

        fun setBubblePref(context: Context, enabled: Boolean) {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BUBBLE, enabled)
            if (enabled) {
                // Re-enabling from the app resets the position, so a bubble that
                // was dragged out of reach can always be recovered.
                editor.remove(KEY_BUBBLE_X).remove(KEY_BUBBLE_Y)
                instance?.resetBubblePosition()
            }
            editor.apply()
            instance?.applyBubbleState()
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var overlayView: CropScreen? = null
    private var overlayBitmap: Bitmap? = null

    // Last bubble position (persisted), so it reappears where the user left it.
    private var bubbleX = UNSET
    private var bubbleY = UNSET

    // True only while the user has actively dismissed the keyguard (ACTION_USER_
    // _PRESENT). Reset when the screen sleeps/dozes. This — unlike the keyguard
    // "locked" flag — stays false on a trusted lock screen / AOD (Extend Unlock).
    private var userPresent = false

    // True while a dream (AOD, screensaver) runs. Needed because the screen-
    // timeout path goes Awake -> Dreaming, where the device still counts as
    // "interactive" and no SCREEN_OFF is sent — without this the bubble would
    // stay visible on top of AOD until the lock screen appears.
    private var dreaming = false

    // True while the screenshot tool is open (or opening) via ANY path — the
    // bubble tap, the assistant session, etc. The bubble hides and stays hidden
    // while this is set, so it never lands in the shot or stacks over the tool.
    private var capturing = false

    // Bumped when the assistant takes over (setCapturing(true)) so an in-flight
    // bubble-tap capture knows it was superseded and must not present its
    // overlay on top of the assistant's.
    private var bubbleCaptureGen = 0

    // The generation that presented the currently open bubble overlay. Its
    // dismissal may only clear the `capturing` latch while still current — a
    // Cancel/Share click dispatched just as the assistant takes over must not
    // release the latch the assistant now owns (re-showing the bubble on top).
    private var overlayGen = -1

    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    // Re-evaluate as the screen locks/unlocks/starts dreaming.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> userPresent = true
                Intent.ACTION_SCREEN_OFF -> userPresent = false
                // Lock screen "None" never sends ACTION_USER_PRESENT: with no
                // keyguard security, waking the screen IS user presence. Secure
                // devices (incl. Extend Unlock) still wait for USER_PRESENT.
                Intent.ACTION_SCREEN_ON ->
                    if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) {
                        userPresent = true
                    }
                Intent.ACTION_DREAMING_STARTED -> {
                    dreaming = true
                    userPresent = false
                }
                Intent.ACTION_DREAMING_STOPPED -> {
                    dreaming = false
                    // Waking within the keyguard grace period never shows the
                    // lock screen, so no USER_PRESENT follows — returning to a
                    // still-unlocked screen IS presence.
                    if (isScreenInteractive() && !keyguardLocked()) userPresent = true
                }
            }
            refreshBubble()
        }
    }

    // Catches AOD/doze transitions, which don't reliably send ACTION_SCREEN_OFF.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            if (!isScreenInteractive()) userPresent = false
            // Rotation fires this too — keep an open overlay covering the
            // whole (re-oriented) screen.
            resizeOverlayToDisplay()
            refreshBubble()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        bubbleX = prefs.getInt(KEY_BUBBLE_X, UNSET)
        bubbleY = prefs.getInt(KEY_BUBBLE_Y, UNSET)
        // The service is enabled while the user is unlocked and using the phone.
        val keyguard = getSystemService(KeyguardManager::class.java)
        userPresent = isScreenInteractive() && !keyguard.isKeyguardLocked
        // Protected system broadcasts — no permission or exported flag needed.
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
            }
        )
        displayManager.registerDisplayListener(displayListener, ui)
        refreshBubble()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        teardownOverlay()
        removeBubble()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        teardownOverlay()
        removeBubble()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window transitions (e.g. the keyguard appearing over the crop overlay)
        // re-evaluate — this is what catches the lock screen while still awake.
        refreshBubble()
    }

    override fun onInterrupt() = Unit

    // --- Screen capture: bubble path + assistant fallback/clean re-capture --

    fun capture(callback: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    // Report failure rather than throw (e.g. OOM copying a
                    // large buffer) — an unreported failure would leave the
                    // `capturing` latch stuck and the tool dead until the
                    // service is toggled.
                    val result = try {
                        Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (t: Throwable) {
                        null
                    } finally {
                        runCatching { screenshot.hardwareBuffer.close() }
                    }
                    callback(result)
                }

                override fun onFailure(errorCode: Int) = callback(null)
            }
        )
    }

    // --- Floating bubble ----------------------------------------------------

    /** Re-apply the pref (called when the toggle changes). */
    fun applyBubbleState() = refreshBubble()

    /** Forget the saved position so the bubble returns to its default spot. */
    fun resetBubblePosition() {
        bubbleX = UNSET
        bubbleY = UNSET
    }

    /** True when the screen is awake — but beware: dreaming (AOD after a screen
     *  timeout) still counts as interactive, hence the separate [dreaming] flag. */
    private fun isScreenInteractive(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive

    private fun keyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** The screen is awake and unlocked — the tool/bubble may be shown. */
    private fun isScreenUsable(): Boolean =
        isScreenInteractive() && !dreaming && !keyguardLocked()

    /** Bubble is shown only when enabled, no tool open, the user is present,
     *  and the screen is usable — hidden on lock screen and AOD even when the
     *  device is trust-unlocked (Extend Unlock). */
    private fun shouldShowBubble(): Boolean =
        isBubbleEnabled(this) && !capturing && overlayView == null &&
            userPresent && isScreenUsable()

    private fun refreshBubble() {
        ui.post {
            // Close the crop tool if the screen went to sleep/AOD/lock while it
            // was open, so it isn't left interactive on the lock screen.
            if (overlayView != null && !isScreenUsable()) {
                teardownOverlay()
                if (overlayGen == bubbleCaptureGen) capturing = false
            }
            if (shouldShowBubble()) addBubble() else removeBubble()
        }
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
        val metrics = resources.displayMetrics
        if (bubbleX == UNSET || bubbleY == UNSET) {
            // First run: default to the right edge, a third of the way down.
            bubbleX = metrics.widthPixels - size - dp(8)
            bubbleY = metrics.heightPixels / 3
        }
        // Keep it on-screen (e.g. after a rotation or resolution change).
        bubbleX = bubbleX.coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
        bubbleY = bubbleY.coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
        }
        attachBubbleTouch(icon, params)
        // The window token dies with the service: a refreshBubble posted just
        // before unbind can run after it, where addView throws BadTokenException.
        if (runCatching { windowManager.addView(icon, params) }.isFailure) return
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
                    bubbleX = params.x
                    bubbleY = params.y
                    runCatching { windowManager.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick(); triggerCapture()
                    } else {
                        persistBubblePosition()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // A drag can end in a cancel (a system gesture steals the
                    // pointer); the bubble already moved, so persist like UP.
                    if (moved) persistBubblePosition()
                    true
                }
                else -> false
            }
        }
    }

    private fun persistBubblePosition() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_BUBBLE_X, bubbleX)
            .putInt(KEY_BUBBLE_Y, bubbleY)
            .apply()
    }

    /**
     * Hide the bubble (so it isn't in the shot), then grab the screen and
     * show the crop overlay. Called when the bubble is tapped.
     */
    fun triggerCapture() {
        if (capturing) return // a tool is already open/opening
        capturing = true
        val gen = ++bubbleCaptureGen
        removeBubble()
        ui.postDelayed({
            if (gen != bubbleCaptureGen) return@postDelayed // assistant took over
            capture { bitmap ->
                ui.post {
                    if (gen != bubbleCaptureGen) return@post // assistant took over
                    if (bitmap != null) presentOverlay(bitmap) else {
                        Toast.makeText(
                            this, R.string.error_capture_failed, Toast.LENGTH_LONG
                        ).show()
                        capturing = false
                        refreshBubble()
                    }
                }
            }
        }, 80)
    }

    /**
     * Called by the assistant session so the bubble hides — and stays hidden —
     * while its crop overlay is up, and can't be tapped to stack a second tool.
     */
    fun setCapturing(active: Boolean) {
        capturing = active
        if (active) {
            // Also close the bubble-path overlay — and cancel any bubble
            // capture still in flight — so the assistant never stacks on top
            // of it: only one tool is ever open.
            bubbleCaptureGen++
            ui.post { teardownOverlay(); removeBubble() }
        } else {
            refreshBubble()
        }
    }

    /** True if the floating bubble is currently on screen. */
    fun isBubbleShowing(): Boolean = bubbleView != null

    /** The clean screenshot behind the open bubble crop overlay (no bubble in
     *  it), for the assistant to reuse instead of a system shot that would
     *  include the overlay. Null if no bubble overlay is open. */
    fun openOverlayScreenshot(): Bitmap? = if (overlayView != null) overlayBitmap else null

    private fun presentOverlay(bitmap: Bitmap) {
        ShareHelper.cleanupCache(this)
        teardownOverlay()
        overlayGen = bubbleCaptureGen
        overlayBitmap = bitmap

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

    /** Re-fit an open crop overlay to the current display bounds (rotation). */
    private fun resizeOverlayToDisplay() {
        val view = overlayView ?: return
        val bounds = windowManager.currentWindowMetrics.bounds
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.width == bounds.width() && params.height == bounds.height()) return
        params.width = bounds.width()
        params.height = bounds.height()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun dismissOverlay() {
        teardownOverlay()
        if (overlayGen != bubbleCaptureGen) return // assistant owns the latch now
        capturing = false
        refreshBubble()
    }

    private fun teardownOverlay() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        overlayBitmap = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
