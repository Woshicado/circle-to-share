package eu.woshicado.circletoshare

import android.content.Context
import android.graphics.Rect

/** Small app-wide preferences (same store the accessibility service uses). */
object Prefs {
    private const val NAME = "cts"
    private const val KEY_FREEFORM = "freeform_crop"
    // Last delivered crop, in bitmap pixels, plus the source dimensions it was
    // taken against. Only a rectangle is stored — never any image data.
    private const val KEY_CROP_L = "last_crop_l"
    private const val KEY_CROP_T = "last_crop_t"
    private const val KEY_CROP_R = "last_crop_r"
    private const val KEY_CROP_B = "last_crop_b"
    private const val KEY_CROP_W = "last_crop_w"
    private const val KEY_CROP_H = "last_crop_h"

    /** True = draw a freeform circle (default); false = drag a rectangle. */
    fun isFreeformCrop(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FREEFORM, true)

    fun setFreeformCrop(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FREEFORM, enabled).apply()
    }

    /** Remember the last crop so it can be re-applied to a matching capture. */
    fun saveLastCrop(context: Context, rect: Rect, srcWidth: Int, srcHeight: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CROP_L, rect.left)
            .putInt(KEY_CROP_T, rect.top)
            .putInt(KEY_CROP_R, rect.right)
            .putInt(KEY_CROP_B, rect.bottom)
            .putInt(KEY_CROP_W, srcWidth)
            .putInt(KEY_CROP_H, srcHeight)
            .apply()
    }

    /**
     * The saved crop, but only if it was taken against a capture of the given
     * dimensions — a rectangle from a differently-sized screen is meaningless.
     * Returns null when nothing is stored or the dimensions don't match.
     */
    fun getLastCropFor(context: Context, srcWidth: Int, srcHeight: Int): Rect? {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (p.getInt(KEY_CROP_W, 0) != srcWidth || p.getInt(KEY_CROP_H, 0) != srcHeight) {
            return null
        }
        val rect = Rect(
            p.getInt(KEY_CROP_L, 0),
            p.getInt(KEY_CROP_T, 0),
            p.getInt(KEY_CROP_R, 0),
            p.getInt(KEY_CROP_B, 0)
        )
        return if (rect.width() > 0 && rect.height() > 0) rect else null
    }
}
