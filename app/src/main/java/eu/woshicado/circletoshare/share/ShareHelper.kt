package eu.woshicado.circletoshare.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import eu.woshicado.circletoshare.R
import java.io.File

/**
 * Crops live only in the app's cache dir — never in the gallery. Old files
 * are swept on every session so nothing needs manual cleanup.
 */
object ShareHelper {

    private const val AUTHORITY = "eu.woshicado.circletoshare.fileprovider"
    private const val DIR = "shots"
    private const val MAX_AGE_MS = 60L * 60L * 1000L // 1 hour

    /**
     * Crops [source] to [rect] (null = whole bitmap) and either opens the share
     * sheet or copies to the clipboard. Returns false if there was nothing valid
     * to deliver. Throws only on unexpected IO failure (caller should toast).
     */
    fun deliver(context: Context, source: Bitmap, rect: Rect?, share: Boolean): Boolean {
        val bounds = (rect ?: Rect(0, 0, source.width, source.height)).apply {
            intersect(0, 0, source.width, source.height)
        }
        if (bounds.width() < 4 || bounds.height() < 4) return false

        val cropped = Bitmap.createBitmap(
            source, bounds.left, bounds.top, bounds.width(), bounds.height()
        )
        val uri = writeAndGetUri(context, cropped)

        if (share) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("screenshot", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        } else {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "screenshot", uri))
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    fun writeAndGetUri(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, "crop-${System.currentTimeMillis()}.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return FileProvider.getUriForFile(context, AUTHORITY, file)
    }

    fun cleanupCache(context: Context) {
        val dir = File(context.cacheDir, DIR)
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
