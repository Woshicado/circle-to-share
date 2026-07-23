package eu.woshicado.circletoshare.crop

import android.graphics.Rect

/**
 * UI-element bounds captured alongside a screenshot, in screen pixels, plus
 * the display size they were measured against — so they can be scaled onto
 * the bitmap even if its resolution differs from the screen's.
 */
data class SnapBounds(
    val rects: List<Rect>,
    val screenWidth: Int,
    val screenHeight: Int
)
