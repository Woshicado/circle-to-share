package com.joshua.circletoshare.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.widget.Toast
import androidx.core.view.WindowCompat
import com.joshua.circletoshare.R
import com.joshua.circletoshare.capture.ScreenshotAccessibilityService
import com.joshua.circletoshare.crop.CropScreen
import com.joshua.circletoshare.share.ShareHelper

/**
 * Full-screen overlay shown when the assistant gesture fires: displays the
 * frozen screenshot, lets the user crop, then shares or copies the result
 * without ever writing to the gallery.
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {

    private val ui = Handler(Looper.getMainLooper())
    private var screenshot: Bitmap? = null
    private lateinit var cropScreen: CropScreen

    private val screenshotTimeout = Runnable {
        if (screenshot == null) captureViaAccessibility()
    }

    override fun onCreateContentView(): View {
        cropScreen = CropScreen(context).apply {
            callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(share: Boolean, rect: Rect?) = deliver(share, rect)
                override fun onCancel() = hide()
            }
        }
        return cropScreen
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        window?.window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        screenshot = null
        // Hide the floating bubble so it doesn't linger over this overlay.
        ScreenshotAccessibilityService.instance?.setBubbleHidden(true)
        ShareHelper.cleanupCache(context)
        // The system delivers the screenshot right after onShow. If it never
        // arrives (OEM/setting), fall back to the accessibility capture.
        ui.postDelayed(screenshotTimeout, 450)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        ui.removeCallbacks(screenshotTimeout)
        if (screenshot != null) onScreenshotReady(screenshot) else captureViaAccessibility()
    }

    override fun onHide() {
        ui.removeCallbacks(screenshotTimeout)
        screenshot = null
        // Bring the bubble back (if the user has it enabled).
        ScreenshotAccessibilityService.instance?.setBubbleHidden(false)
        super.onHide()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.contentInsets.setEmpty()
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    private fun captureViaAccessibility() {
        val service = ScreenshotAccessibilityService.instance
        if (service == null) {
            fail(context.getString(R.string.error_no_capture))
            return
        }
        service.capture { bitmap ->
            ui.post {
                if (bitmap != null) onScreenshotReady(bitmap)
                else fail(context.getString(R.string.error_capture_failed))
            }
        }
    }

    private fun onScreenshotReady(bitmap: Bitmap) {
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        screenshot = software
        cropScreen.setBitmap(software)
    }

    private fun deliver(share: Boolean, rect: Rect?) {
        val bitmap = screenshot ?: return
        val delivered = try {
            ShareHelper.deliver(context, bitmap, rect, share)
        } catch (e: Exception) {
            fail(context.getString(R.string.error_capture_failed))
            return
        }
        if (delivered) hide()
    }

    private fun fail(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        hide()
    }
}
