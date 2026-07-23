package com.joshua.circletoshare.crop

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.joshua.circletoshare.R

/**
 * The full crop overlay UI — frozen screenshot, draggable crop rectangle, and
 * the Cancel/Copy/Share pill bar. Shared by both the assistant session and the
 * floating-bubble path so the two behave identically.
 */
class CropScreen(context: Context) : FrameLayout(context) {

    interface Callbacks {
        /** [rect] is in bitmap coordinates, or null to share the whole screen. */
        fun onDeliver(share: Boolean, rect: Rect?)
        fun onCancel()
    }

    var callbacks: Callbacks? = null

    private val cropView = CropOverlayView(context)
    private val hintView: TextView
    private val buttonBar: LinearLayout
    private val shareButton: TextView

    init {
        cropView.visibility = View.GONE
        cropView.listener = object : CropOverlayView.Listener {
            override fun onSelectionChanged(hasSelection: Boolean) {
                shareButton.text = context.getString(
                    if (hasSelection) R.string.action_share else R.string.action_share_full
                )
                hintView.visibility = if (hasSelection) View.GONE else View.VISIBLE
            }
        }
        addView(
            cropView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        hintView = TextView(context).apply {
            text = context.getString(R.string.crop_hint)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = pill(Color.argb(200, 30, 30, 30), 24)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            visibility = View.GONE
        }
        addView(
            hintView,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(64) }
        )

        buttonBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val cancel = pillButton(context.getString(R.string.action_cancel), filled = false) {
            callbacks?.onCancel()
        }
        val copy = pillButton(context.getString(R.string.action_copy), filled = false) {
            deliver(share = false)
        }
        shareButton = pillButton(context.getString(R.string.action_share_full), filled = true) {
            deliver(share = true)
        }
        buttonBar.addView(cancel, barItem())
        buttonBar.addView(copy, barItem())
        buttonBar.addView(shareButton, barItem())
        addView(
            buttonBar,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(48) }
        )
    }

    fun setBitmap(bitmap: android.graphics.Bitmap) {
        // Apply the current crop-gesture preference (freeform circle vs. box).
        val freeform = com.joshua.circletoshare.Prefs.isFreeformCrop(context)
        cropView.freeform = freeform
        hintView.setText(if (freeform) R.string.crop_hint_freeform else R.string.crop_hint)

        setBackgroundColor(Color.BLACK)
        cropView.setBitmap(bitmap)
        cropView.visibility = View.VISIBLE
        hintView.visibility = View.VISIBLE
        buttonBar.visibility = View.VISIBLE
        shareButton.text = context.getString(R.string.action_share_full)
    }

    /** Drop the frozen screenshot and hide the UI — the reverse of [setBitmap].
     *  Called when the overlay is dismissed so a reused instance can't flash
     *  the previous session's image. */
    fun clear() {
        background = null
        cropView.clear()
        cropView.visibility = View.GONE
        hintView.visibility = View.GONE
        buttonBar.visibility = View.GONE
    }

    private fun deliver(share: Boolean) {
        callbacks?.onDeliver(share, cropView.getCropRect())
    }

    private fun pillButton(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (filled) Color.BLACK else Color.WHITE)
            background = pill(if (filled) Color.WHITE else Color.argb(230, 45, 45, 45), 26)
            setPadding(dp(24), dp(13), dp(24), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun barItem() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        marginStart = dp(6)
        marginEnd = dp(6)
    }

    private fun pill(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
