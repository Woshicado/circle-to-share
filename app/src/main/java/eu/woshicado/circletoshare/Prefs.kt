package eu.woshicado.circletoshare

import android.content.Context

/** Small app-wide preferences (same store the accessibility service uses). */
object Prefs {
    private const val NAME = "cts"
    private const val KEY_FREEFORM = "freeform_crop"

    /** True = draw a freeform circle (default); false = drag a rectangle. */
    fun isFreeformCrop(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FREEFORM, true)

    fun setFreeformCrop(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FREEFORM, enabled).apply()
    }
}
