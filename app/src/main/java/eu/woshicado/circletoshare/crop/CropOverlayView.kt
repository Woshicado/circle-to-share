package eu.woshicado.circletoshare.crop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the frozen screenshot and a draggable/resizable crop rectangle with
 * Circle-to-Search-style corner handles.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onSelectionChanged(hasSelection: Boolean)
    }

    var listener: Listener? = null

    /**
     * When true (default), the first gesture is a freeform "circle" — the user
     * draws a loop/scribble and its bounding box becomes the crop box. When
     * false, the gesture rubber-bands a rectangle corner-to-corner. Either way
     * the resulting box is resized/moved with the same handles.
     */
    var freeform: Boolean = true

    private var bitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val imageBounds = RectF()

    private var selection: RectF? = null

    // Freeform stroke state (only used while drawing a circle).
    private val strokePath = Path()
    private var hasStroke = false
    private var strokeMinX = 0f
    private var strokeMinY = 0f
    private var strokeMaxX = 0f
    private var strokeMaxY = 0f

    private enum class Mode { NONE, DRAWING, MOVING, RESIZING }

    private var mode = Mode.NONE
    private var activeCorner = -1 // 0=TL 1=TR 2=BL 3=BR
    private var downX = 0f
    private var downY = 0f
    private var moveOffsetX = 0f
    private var moveOffsetY = 0f

    private val handleTouchRadius = dp(28f)
    private val minSelection = dp(24f)
    private val cornerLength = dp(20f)
    // Breathing room so a hand-drawn loop doesn't clip the circled content.
    private val strokePadding = dp(10f)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(1.5f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(235, 255, 255, 255)
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setBitmap(b: Bitmap) {
        bitmap = b
        selection = null
        mode = Mode.NONE
        resetStroke()
        updateMatrix()
        listener?.onSelectionChanged(false)
        invalidate()
    }

    fun clear() {
        bitmap = null
        selection = null
        mode = Mode.NONE
        resetStroke()
        invalidate()
    }

    private fun resetStroke() {
        strokePath.reset()
        hasStroke = false
    }

    /** Selected region in bitmap coordinates, or null when nothing is selected. */
    fun getCropRect(): Rect? {
        val sel = selection ?: return null
        val b = bitmap ?: return null
        val mapped = RectF(sel)
        inverseMatrix.mapRect(mapped)
        val rect = Rect(
            max(0, mapped.left.toInt()),
            max(0, mapped.top.toInt()),
            min(b.width, mapped.right.toInt()),
            min(b.height, mapped.bottom.toInt())
        )
        return if (rect.width() > 4 && rect.height() > 4) rect else null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
        // A screen-space selection is meaningless after a relayout (rotation):
        // it maps to the wrong bitmap region and can exceed the new image
        // bounds, making moveTo's coerceIn throw. Start over.
        if (selection != null) {
            selection = null
            mode = Mode.NONE
            activeCorner = -1
            resetStroke()
            listener?.onSelectionChanged(false)
        }
    }

    private fun updateMatrix() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val src = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
        imageMatrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER)
        imageMatrix.mapRect(imageBounds, src)
        imageMatrix.invert(inverseMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, imageMatrix, bitmapPaint)

        val sel = selection
        if (sel == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            // Show the freeform "circle" trail as the finger moves.
            if (hasStroke) canvas.drawPath(strokePath, strokePaint)
            return
        }

        // Dim everything outside the selection.
        canvas.drawRect(0f, 0f, width.toFloat(), sel.top, dimPaint)
        canvas.drawRect(0f, sel.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, sel.top, sel.left, sel.bottom, dimPaint)
        canvas.drawRect(sel.right, sel.top, width.toFloat(), sel.bottom, dimPaint)

        canvas.drawRect(sel, borderPaint)

        val l = min(cornerLength, min(sel.width(), sel.height()) / 3f)
        // Corner brackets: TL, TR, BL, BR
        canvas.drawLine(sel.left, sel.top, sel.left + l, sel.top, handlePaint)
        canvas.drawLine(sel.left, sel.top, sel.left, sel.top + l, handlePaint)
        canvas.drawLine(sel.right, sel.top, sel.right - l, sel.top, handlePaint)
        canvas.drawLine(sel.right, sel.top, sel.right, sel.top + l, handlePaint)
        canvas.drawLine(sel.left, sel.bottom, sel.left + l, sel.bottom, handlePaint)
        canvas.drawLine(sel.left, sel.bottom, sel.left, sel.bottom - l, handlePaint)
        canvas.drawLine(sel.right, sel.bottom, sel.right - l, sel.bottom, handlePaint)
        canvas.drawLine(sel.right, sel.bottom, sel.right, sel.bottom - l, handlePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        val x = event.x.coerceIn(imageBounds.left, imageBounds.right)
        val y = event.y.coerceIn(imageBounds.top, imageBounds.bottom)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                val sel = selection
                val corner = sel?.let { hitCorner(it, x, y) } ?: -1
                mode = when {
                    corner >= 0 -> {
                        activeCorner = corner
                        Mode.RESIZING
                    }
                    sel != null && sel.contains(x, y) -> {
                        moveOffsetX = x - sel.left
                        moveOffsetY = y - sel.top
                        Mode.MOVING
                    }
                    else -> Mode.DRAWING
                }
                if (mode == Mode.DRAWING && freeform) {
                    // Start a fresh circle: drop any old box, begin the trail.
                    selection = null
                    strokePath.reset()
                    strokePath.moveTo(x, y)
                    strokeMinX = x; strokeMaxX = x
                    strokeMinY = y; strokeMaxY = y
                    hasStroke = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.DRAWING -> {
                        if (freeform) {
                            strokePath.lineTo(x, y)
                            strokeMinX = min(strokeMinX, x); strokeMaxX = max(strokeMaxX, x)
                            strokeMinY = min(strokeMinY, y); strokeMaxY = max(strokeMaxY, y)
                        } else {
                            selection = RectF(
                                min(downX, x), min(downY, y),
                                max(downX, x), max(downY, y)
                            )
                        }
                    }
                    Mode.RESIZING -> selection?.let { resize(it, x, y) }
                    Mode.MOVING -> selection?.let { moveTo(it, x, y) }
                    Mode.NONE -> Unit
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAWING) {
                    if (freeform) {
                        if (hasStroke) {
                            // Bounding box of the drawn loop — padded and clamped
                            // to the image. The loop need not be closed.
                            selection = RectF(
                                max(strokeMinX - strokePadding, imageBounds.left),
                                max(strokeMinY - strokePadding, imageBounds.top),
                                min(strokeMaxX + strokePadding, imageBounds.right),
                                min(strokeMaxY + strokePadding, imageBounds.bottom)
                            )
                        }
                        resetStroke()
                    } else if (hypot(x - downX, y - downY) < dp(8f)) {
                        // A plain tap outside the selection clears it.
                        selection = null
                    }
                }
                // Discard incidental taps / tiny scribbles.
                selection?.let {
                    if (it.width() < minSelection || it.height() < minSelection) {
                        selection = null
                    }
                }
                mode = Mode.NONE
                activeCorner = -1
                listener?.onSelectionChanged(selection != null)
                invalidate()
            }
        }
        return true
    }

    private fun hitCorner(sel: RectF, x: Float, y: Float): Int {
        val corners = arrayOf(
            floatArrayOf(sel.left, sel.top),
            floatArrayOf(sel.right, sel.top),
            floatArrayOf(sel.left, sel.bottom),
            floatArrayOf(sel.right, sel.bottom)
        )
        for (i in corners.indices) {
            if (abs(x - corners[i][0]) < handleTouchRadius &&
                abs(y - corners[i][1]) < handleTouchRadius
            ) {
                return i
            }
        }
        return -1
    }

    private fun resize(sel: RectF, x: Float, y: Float) {
        when (activeCorner) {
            0 -> { sel.left = x; sel.top = y }
            1 -> { sel.right = x; sel.top = y }
            2 -> { sel.left = x; sel.bottom = y }
            3 -> { sel.right = x; sel.bottom = y }
        }
        // Flip the active corner when the user drags past the opposite edge.
        if (sel.left > sel.right) {
            val t = sel.left; sel.left = sel.right; sel.right = t
            activeCorner = when (activeCorner) {
                0 -> 1; 1 -> 0; 2 -> 3; else -> 2
            }
        }
        if (sel.top > sel.bottom) {
            val t = sel.top; sel.top = sel.bottom; sel.bottom = t
            activeCorner = when (activeCorner) {
                0 -> 2; 2 -> 0; 1 -> 3; else -> 1
            }
        }
    }

    private fun moveTo(sel: RectF, x: Float, y: Float) {
        val w = sel.width()
        val h = sel.height()
        var left = x - moveOffsetX
        var top = y - moveOffsetY
        // The upper bound can fall below the lower one if the selection is ever
        // wider/taller than the image — clamp it so coerceIn can't throw.
        left = left.coerceIn(imageBounds.left, max(imageBounds.left, imageBounds.right - w))
        top = top.coerceIn(imageBounds.top, max(imageBounds.top, imageBounds.bottom - h))
        sel.set(left, top, left + w, top + h)
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
