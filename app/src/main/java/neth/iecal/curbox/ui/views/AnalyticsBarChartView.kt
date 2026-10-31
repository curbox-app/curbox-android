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
 * A compact bar chart for many bars, such as 24 hours or 90 days. Only every [labelEvery] label is
 * drawn so dense charts stay readable. Tapping a bar selects it and reports its index.
 */
class AnalyticsBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var values: List<Float> = emptyList()
    private var labels: List<String> = emptyList()
    private var labelEvery = 1
    private var selectedIndex = -1
    private var averageLine = -1f
    private var onBarSelected: ((Int) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emptyBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val averagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val barRect = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    fun setData(values: List<Float>, labels: List<String>, labelEvery: Int = 1, selected: Int = -1, average: Float = -1f) {
        this.values = values
        this.labels = labels
        this.labelEvery = labelEvery.coerceAtLeast(1)
        selectedIndex = selected
        averageLine = average
        resolveColors()
        invalidate()
    }

    fun setOnBarSelectedListener(listener: (Int) -> Unit) {
        onBarSelected = listener
    }

    private fun resolveColors() {
        val colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val colorOnSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val colorTrack = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest)
        val density = resources.displayMetrics.density

        barPaint.color = ColorUtils.setAlphaComponent(colorPrimary, 150)
        selectedBarPaint.color = colorPrimary
        emptyBarPaint.color = colorTrack
        averagePaint.color = ColorUtils.setAlphaComponent(colorOnSurfaceVariant, 140)
        averagePaint.strokeWidth = 1.5f * density
        averagePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
        labelPaint.color = colorOnSurfaceVariant
        labelPaint.textSize = 10f * density
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveColors()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (150 * resources.displayMetrics.density).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val density = resources.displayMetrics.density
        val labelArea = 20f * density
        val chartTop = 8f * density
        val chartBottom = height - labelArea - paddingBottom
        val maxHeight = chartBottom - chartTop
        val left = paddingStart.toFloat()
        val slot = (width - paddingStart - paddingEnd).toFloat() / values.size
        val barWidth = (slot * 0.62f).coerceAtMost(28f * density)
        val radius = (barWidth / 2f).coerceAtMost(6f * density)
        val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f

        values.forEachIndexed { index, value ->
            val cx = left + slot * index + slot / 2f
            var barHeight = maxHeight * (value / max)
            val paint = when {
                value <= 0f -> emptyBarPaint
                index == selectedIndex -> selectedBarPaint
                else -> barPaint
            }
            if (value <= 0f) barHeight = 3f * density
            barHeight = barHeight.coerceAtLeast(3f * density)
            barRect.set(cx - barWidth / 2f, chartBottom - barHeight, cx + barWidth / 2f, chartBottom)
            canvas.drawRoundRect(barRect, radius, radius, paint)

            if ((index % labelEvery == 0 || index == selectedIndex) && index < labels.size) {
                labelPaint.isFakeBoldText = index == selectedIndex
                labelPaint.color = if (index == selectedIndex) selectedBarPaint.color
                else MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
                canvas.drawText(labels[index], cx, chartBottom + labelArea * 0.75f, labelPaint)
            }
        }

        if (averageLine > 0f) {
            val y = chartBottom - maxHeight * (averageLine / max)
            canvas.drawLine(left, y, width - paddingEnd.toFloat(), y, averagePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.isEmpty()) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) {
            val slot = (width - paddingStart - paddingEnd).toFloat() / values.size
            val index = ((event.x - paddingStart) / slot).toInt().coerceIn(0, values.lastIndex)
            selectedIndex = index
            invalidate()
            onBarSelected?.invoke(index)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
