package neth.iecal.curbox.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * A grid with one row per weekday and one column per hour. Darker cells mean more phone time.
 * Tapping a cell reports its row and column.
 */
class UsageHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var cells: Array<LongArray> = emptyArray()
    private var rowLabels: List<String> = emptyList()
    private var columnLabels: Map<Int, String> = emptyMap()
    private var selected: Pair<Int, Int>? = null
    private var onCellSelected: ((Int, Int) -> Unit)? = null

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellRect = RectF()
    private var colorPrimary = 0
    private var colorEmpty = 0

    init {
        isClickable = true
        isFocusable = true
    }

    fun setData(cells: Array<LongArray>, rowLabels: List<String>, columnLabels: Map<Int, String>) {
        this.cells = cells
        this.rowLabels = rowLabels
        this.columnLabels = columnLabels
        selected = null
        resolveColors()
        requestLayout()
        invalidate()
    }

    fun setOnCellSelectedListener(listener: (row: Int, column: Int) -> Unit) {
        onCellSelected = listener
    }

    private fun resolveColors() {
        val density = resources.displayMetrics.density
        colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        colorEmpty = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest)
        selectedPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        selectedPaint.strokeWidth = 1.5f * density
        labelPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        labelPaint.textSize = 10f * density
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveColors()
    }

    private val rowLabelWidth get() = 34f * resources.displayMetrics.density
    private val columnLabelHeight get() = 18f * resources.displayMetrics.density

    private fun cellSize(width: Int): Float {
        val columns = cells.firstOrNull()?.size ?: 24
        return (width - paddingStart - paddingEnd - rowLabelWidth) / columns
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = cells.size.coerceAtLeast(7)
        val height = (cellSize(width) * rows + columnLabelHeight + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cells.isEmpty()) return
        val density = resources.displayMetrics.density
        val size = cellSize(width)
        val gap = 1.5f * density
        val radius = 3f * density
        val left = paddingStart + rowLabelWidth
        val top = paddingTop.toFloat()
        val max = cells.maxOf { row -> row.maxOrNull() ?: 0L }.coerceAtLeast(1L)

        cells.forEachIndexed { row, values ->
            labelPaint.textAlign = Paint.Align.LEFT
            rowLabels.getOrNull(row)?.let {
                canvas.drawText(it, paddingStart.toFloat(), top + size * row + size * 0.68f, labelPaint)
            }
            values.forEachIndexed { column, value ->
                cellPaint.color = if (value <= 0L) colorEmpty
                else ColorUtils.setAlphaComponent(colorPrimary, (40 + 215 * value / max).toInt().coerceIn(40, 255))
                cellRect.set(left + size * column + gap, top + size * row + gap,
                    left + size * (column + 1) - gap, top + size * (row + 1) - gap)
                canvas.drawRoundRect(cellRect, radius, radius, cellPaint)
                if (selected == row to column) canvas.drawRoundRect(cellRect, radius, radius, selectedPaint)
            }
        }

        labelPaint.textAlign = Paint.Align.CENTER
        val labelY = top + size * cells.size + columnLabelHeight * 0.8f
        columnLabels.forEach { (column, label) ->
            canvas.drawText(label, left + size * column + size / 2f, labelY, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cells.isEmpty()) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) {
            val size = cellSize(width)
            val column = ((event.x - paddingStart - rowLabelWidth) / size).toInt()
            val row = ((event.y - paddingTop) / size).toInt()
            if (row in cells.indices && column in 0 until cells[row].size) {
                selected = row to column
                invalidate()
                onCellSelected?.invoke(row, column)
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
