package neth.iecal.curbox.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.random.Random

data class Contributor(val name: String, val contributions: Int)

/**
 * Contributor names packed into the Curbox logo, measured in the logo's 800 by 800 viewport.
 * Every contributor appears once, sized by contributions. Filler copies then complete the shape.
 */
class ContributorCloud(val words: List<Word>, val bounds: RectF) {

    class Word(
        val contributor: Contributor,
        val textSize: Float,
        val vertical: Boolean,
        val isFiller: Boolean,
        /** Tight glyph box in logo units. */
        val box: RectF,
        /** Offset from the box's reading start corner to the text origin. */
        val originX: Float,
        val originY: Float,
    )

    companion object {
        private const val VIEWPORT = 800
        private const val CELL = 2.5f
        private const val GRID = (VIEWPORT / CELL).toInt()

        // The launcher logo stroke, thickened so names stay readable.
        internal const val LOGO_PATH =
            "M266.667 605.923C266.667 775.79 533.333 775.79 533.333 605.923C533.333 436.09 266.667 368.157 266.667 181.292C266.667 28.4583 533.333 28.4583 533.333 181.292C533.333 368.157 266.667 436.093 266.667 605.96V605.923Z"
        internal const val LOGO_STROKE = 84f
        internal val LOGO_GAP = RectF(440f, 578f, 700f, 800f)

        private const val MAX_NAME_SIZE = 58f
        private const val MIN_NAME_SIZE = 15f
        private const val SMALLEST_NAME_SIZE = 9f
        private val FILLER_SIZES = floatArrayOf(12f, 10f, 8.5f, 7f)
        private const val MAX_WORDS = 2500

        /** CPU heavy. Call off the main thread. */
        fun build(contributors: List<Contributor>, typeface: Typeface?): ContributorCloud {
            val people = contributors.filter { it.name.isNotBlank() }
                .sortedByDescending { it.contributions }
            val free = logoMask()
            val packer = Packer(free, typeface)
            val words = ArrayList<Word>()
            if (people.isEmpty()) return ContributorCloud(words, RectF(0f, 0f, 1f, 1f))

            val most = people.first().contributions.coerceAtLeast(1)
            val random = Random(people.size * 31 + most)
            val shuffled = packer.freeCells().shuffled(random)
            for (person in people) {
                val share = if (most <= 1) 0f else ln(person.contributions.coerceAtLeast(1).toFloat()) / ln(most.toFloat())
                var size = MIN_NAME_SIZE + (MAX_NAME_SIZE - MIN_NAME_SIZE) * share
                while (size >= SMALLEST_NAME_SIZE) {
                    val word = packer.place(person, size, false, shuffled)
                        ?: packer.place(person, size, true, shuffled)
                    if (word != null) {
                        words += word
                        break
                    }
                    size *= 0.92f
                }
            }

            val rowOrder = packer.freeCells()
            var next = 0
            for (size in FILLER_SIZES) {
                val remaining = people.toMutableList()
                while (remaining.isNotEmpty() && words.size < MAX_WORDS) {
                    val person = remaining[next % remaining.size]
                    val word = packer.place(person, size, false, rowOrder, filler = true)
                        ?: packer.place(person, size, true, rowOrder, filler = true)
                    if (word == null) remaining.remove(person) else {
                        words += word
                        next++
                    }
                }
            }

            val bounds = RectF(words.first().box)
            words.forEach { bounds.union(it.box) }
            return ContributorCloud(words, bounds)
        }

        /** True for each grid cell that lies fully inside the logo. */
        private fun logoMask(): BooleanArray {
            val bitmap = Bitmap.createBitmap(GRID, GRID, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(1f / CELL, 1f / CELL)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = LOGO_STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = Color.BLACK
            }
            canvas.drawPath(PathParser.createPathFromPathData(LOGO_PATH), paint)
            paint.style = Paint.Style.FILL
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            canvas.drawRect(LOGO_GAP, paint)

            val pixels = IntArray(GRID * GRID)
            bitmap.getPixels(pixels, 0, GRID, 0, 0, GRID, GRID)
            bitmap.recycle()
            return BooleanArray(pixels.size) { (pixels[it] ushr 24) >= 230 }
        }
    }

    private class Packer(private val free: BooleanArray, typeface: Typeface?) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }
        private val bounds = Rect()

        fun freeCells(): List<Int> = free.indices.filter { free[it] }

        fun place(
            person: Contributor,
            size: Float,
            vertical: Boolean,
            candidates: List<Int>,
            filler: Boolean = false,
        ): Word? {
            paint.textSize = size
            paint.getTextBounds(person.name, 0, person.name.length, bounds)
            val pad = (size * 0.08f).coerceAtLeast(1.5f)
            val along = bounds.width() + pad * 2
            val across = bounds.height() + pad * 2
            val cellsWide = ceil((if (vertical) across else along) / CELL).toInt()
            val cellsHigh = ceil((if (vertical) along else across) / CELL).toInt()
            if (cellsWide >= GRID || cellsHigh >= GRID) return null

            for (cell in candidates) {
                if (!free[cell]) continue
                val column = cell % GRID
                val row = cell / GRID
                if (column + cellsWide > GRID || row + cellsHigh > GRID) continue
                if (!fits(column, row, cellsWide, cellsHigh)) continue

                occupy(column, row, cellsWide, cellsHigh)
                val slotLeft = column * CELL
                val slotTop = row * CELL
                val slotWidth = cellsWide * CELL
                val slotHeight = cellsHigh * CELL
                val textW = bounds.width().toFloat()
                val textH = bounds.height().toFloat()
                val box = if (vertical) {
                    val left = slotLeft + (slotWidth - textH) / 2
                    val top = slotTop + (slotHeight - textW) / 2
                    RectF(left, top, left + textH, top + textW)
                } else {
                    val left = slotLeft + (slotWidth - textW) / 2
                    val top = slotTop + (slotHeight - textH) / 2
                    RectF(left, top, left + textW, top + textH)
                }
                return Word(person, size, vertical, filler, box, -bounds.left.toFloat(), -bounds.top.toFloat())
            }
            return null
        }

        private fun fits(column: Int, row: Int, width: Int, height: Int): Boolean {
            for (y in row until row + height) {
                val start = y * GRID + column
                for (index in start until start + width) if (!free[index]) return false
            }
            return true
        }

        private fun occupy(column: Int, row: Int, width: Int, height: Int) {
            for (y in row until row + height) {
                val start = y * GRID + column
                for (index in start until start + width) free[index] = false
            }
        }
    }
}

/** Draws a [ContributorCloud]. Supports pinch zoom, panning, and double tap zoom. */
class ContributorsCloudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var cloud: ContributorCloud? = null

    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fillerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ContributorCloud.LOGO_STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val outlinePath = PathParser.createPathFromPathData(ContributorCloud.LOGO_PATH)

    private val cloudMatrix = Matrix()
    private val mapped = RectF()
    private var fitScale = 1f
    private var zoomAnimator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            zoomAnimator?.cancel()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            cloudMatrix.postTranslate(-distanceX, -distanceY)
            keepInView()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (currentScale() > fitScale * 1.5f) fitScale else fitScale * 3f
            animateZoomTo(target, e.x, e.y)
            return true
        }
    })

    init {
        isClickable = true
        isFocusable = true
    }

    fun setCloud(cloud: ContributorCloud, typeface: Typeface?) {
        this.cloud = cloud
        namePaint.typeface = typeface
        fillerPaint.typeface = typeface
        resolveColors()
        resetZoom()
        invalidate()
    }

    private fun resolveColors() {
        namePaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val variant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        fillerPaint.color = ColorUtils.setAlphaComponent(variant, 90)
        outlinePaint.color = ColorUtils.setAlphaComponent(namePaint.color, 20)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveColors()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    private fun resetZoom() {
        val bounds = cloud?.bounds ?: return
        if (width == 0 || height == 0) return
        val inset = 16f * resources.displayMetrics.density
        val available = RectF(inset, inset, width - inset, height - inset)
        cloudMatrix.setRectToRect(bounds, available, Matrix.ScaleToFit.CENTER)
        fitScale = currentScale()
    }

    private fun currentScale(): Float {
        val values = FloatArray(9)
        cloudMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val scale = currentScale()
        val clamped = (scale * factor).coerceIn(fitScale, fitScale * 8f) / scale
        cloudMatrix.postScale(clamped, clamped, focusX, focusY)
        keepInView()
    }

    private fun animateZoomTo(target: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        var last = currentScale()
        zoomAnimator = ValueAnimator.ofFloat(last, target).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Float
                zoomBy(value / last, focusX, focusY)
                last = value
            }
            start()
        }
    }

    private fun keepInView() {
        val bounds = cloud?.bounds ?: return
        cloudMatrix.mapRect(mapped, bounds)
        val dx = when {
            mapped.width() <= width -> width / 2f - mapped.centerX()
            mapped.left > 0 -> -mapped.left
            mapped.right < width -> width - mapped.right
            else -> 0f
        }
        val dy = when {
            mapped.height() <= height -> height / 2f - mapped.centerY()
            mapped.top > 0 -> -mapped.top
            mapped.bottom < height -> height - mapped.bottom
            else -> 0f
        }
        cloudMatrix.postTranslate(dx, dy)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (currentScale() > fitScale * 1.01f) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val words = cloud?.words ?: return
        canvas.save()
        canvas.concat(cloudMatrix)
        canvas.save()
        canvas.clipOutRect(ContributorCloud.LOGO_GAP)
        canvas.drawPath(outlinePath, outlinePaint)
        canvas.restore()
        for (word in words) {
            val paint = if (word.isFiller) fillerPaint else namePaint
            paint.textSize = word.textSize
            if (word.vertical) {
                canvas.save()
                canvas.translate(word.box.left, word.box.bottom)
                canvas.rotate(-90f)
                canvas.drawText(word.contributor.name, word.originX, word.originY, paint)
                canvas.restore()
            } else {
                canvas.drawText(word.contributor.name, word.box.left + word.originX, word.box.top + word.originY, paint)
            }
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        zoomAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
