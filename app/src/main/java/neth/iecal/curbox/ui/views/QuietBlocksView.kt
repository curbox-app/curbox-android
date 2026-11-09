package neth.iecal.curbox.ui.views

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A calm block placing puzzle used during urge surfing.
 *
 * Drag a piece from the tray onto the board. Full rows and columns fade away. There is
 * no score and no game over: when nothing fits, the board fades clean and play goes on.
 */
class QuietBlocksView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Stops all input, for example when the time is up. */
    var isFrozen = false
        set(value) {
            field = value
            if (value) cancelDrag()
        }

    private val board = IntArray(SIZE * SIZE)
    private val tray = IntArray(TRAY_SLOTS) { -1 }
    private val trayColors = IntArray(TRAY_SLOTS) { 1 }

    private val fadingCells = IntArray(SIZE * SIZE)
    private var fadeAlpha = 0f
    private var fadeAnimator: ValueAnimator? = null

    private var dragSlot = -1
    private var dragX = 0f
    private var dragY = 0f
    private var ghostRow = -1
    private var ghostCol = -1

    private val boardRect = RectF()
    private var cell = 0f
    private var trayTop = 0f
    private var trayCell = 0f

    private val cellRect = RectF()
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockColors = IntArray(4)

    init {
        emptyPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceContainerHighest
        )
        blockColors[1] = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        blockColors[2] = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary)
        blockColors[3] = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
        dealTray()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // The tray needs about half the board's height below it.
        val desired = (width * 1.5f).roundToInt()
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val boardSize = min(w.toFloat(), h / 1.5f)
        cell = boardSize / SIZE
        val left = (w - boardSize) / 2f
        boardRect.set(left, 0f, left + boardSize, boardSize)
        trayTop = boardSize + cell
        trayCell = cell * 0.55f
    }

    override fun onDraw(canvas: Canvas) {
        val gap = cell * 0.08f
        val radius = cell * 0.18f

        for (index in board.indices) {
            val row = index / SIZE
            val col = index % SIZE
            cellRect.set(
                boardRect.left + col * cell + gap,
                boardRect.top + row * cell + gap,
                boardRect.left + (col + 1) * cell - gap,
                boardRect.top + (row + 1) * cell - gap
            )
            val color = board[index]
            if (color == 0) {
                canvas.drawRoundRect(cellRect, radius, radius, emptyPaint)
                if (fadeAlpha > 0f && fadingCells[index] != 0) {
                    blockPaint.color = withAlpha(blockColors[fadingCells[index]], fadeAlpha)
                    canvas.drawRoundRect(cellRect, radius, radius, blockPaint)
                }
            } else {
                blockPaint.color = blockColors[color]
                canvas.drawRoundRect(cellRect, radius, radius, blockPaint)
            }
        }

        if (dragSlot >= 0 && ghostRow >= 0) {
            ghostPaint.color = withAlpha(blockColors[trayColors[dragSlot]], 0.35f)
            for (part in SHAPES[tray[dragSlot]]) {
                val row = ghostRow + part[0]
                val col = ghostCol + part[1]
                cellRect.set(
                    boardRect.left + col * cell + gap,
                    boardRect.top + row * cell + gap,
                    boardRect.left + (col + 1) * cell - gap,
                    boardRect.top + (row + 1) * cell - gap
                )
                canvas.drawRoundRect(cellRect, radius, radius, ghostPaint)
            }
        }

        for (slot in 0 until TRAY_SLOTS) {
            if (tray[slot] < 0 || slot == dragSlot) continue
            val shape = SHAPES[tray[slot]]
            val (centerX, centerY) = slotCenter(slot)
            drawShape(
                canvas,
                shape,
                centerX - shapeWidth(shape) * trayCell / 2f,
                centerY - shapeHeight(shape) * trayCell / 2f,
                trayCell,
                blockColors[trayColors[slot]]
            )
        }

        if (dragSlot >= 0) {
            val shape = SHAPES[tray[dragSlot]]
            val (left, top) = dragPieceTopLeft(shape)
            drawShape(canvas, shape, left, top, cell, blockColors[trayColors[dragSlot]])
        }
    }

    private fun drawShape(canvas: Canvas, shape: Array<IntArray>, left: Float, top: Float, size: Float, color: Int) {
        val gap = size * 0.08f
        val radius = size * 0.18f
        blockPaint.color = color
        for (part in shape) {
            cellRect.set(
                left + part[1] * size + gap,
                top + part[0] * size + gap,
                left + (part[1] + 1) * size - gap,
                top + (part[0] + 1) * size - gap
            )
            canvas.drawRoundRect(cellRect, radius, radius, blockPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isFrozen) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val slot = slotAt(event.x, event.y)
                if (slot < 0 || tray[slot] < 0) return false
                dragSlot = slot
                dragX = event.x
                dragY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                updateGhost()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragSlot < 0) return false
                dragX = event.x
                dragY = event.y
                updateGhost()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragSlot < 0) return false
                if (ghostRow >= 0) place(dragSlot, ghostRow, ghostCol)
                cancelDrag()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDrag()
                return true
            }
        }
        return false
    }

    private fun cancelDrag() {
        dragSlot = -1
        ghostRow = -1
        ghostCol = -1
        invalidate()
    }

    private fun updateGhost() {
        val shape = SHAPES[tray[dragSlot]]
        val (left, top) = dragPieceTopLeft(shape)
        val col = ((left - boardRect.left) / cell).roundToInt()
        val row = ((top - boardRect.top) / cell).roundToInt()
        if (fits(shape, row, col)) {
            ghostRow = row
            ghostCol = col
        } else {
            ghostRow = -1
            ghostCol = -1
        }
    }

    /** The dragged piece floats above the finger so the finger never hides it. */
    private fun dragPieceTopLeft(shape: Array<IntArray>): Pair<Float, Float> {
        val left = dragX - shapeWidth(shape) * cell / 2f
        val top = dragY - shapeHeight(shape) * cell - cell * 1.2f
        return left to top
    }

    private fun place(slot: Int, row: Int, col: Int) {
        val color = trayColors[slot]
        for (part in SHAPES[tray[slot]]) {
            board[(row + part[0]) * SIZE + col + part[1]] = color
        }
        tray[slot] = -1
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

        clearFullLines()
        if (tray.all { it < 0 }) dealTray()
        if (tray.none { it >= 0 && fitsAnywhere(SHAPES[it]) }) startFresh()
    }

    private fun clearFullLines() {
        val toClear = mutableSetOf<Int>()
        for (row in 0 until SIZE) {
            if ((0 until SIZE).all { board[row * SIZE + it] != 0 }) {
                (0 until SIZE).forEach { toClear += row * SIZE + it }
            }
        }
        for (col in 0 until SIZE) {
            if ((0 until SIZE).all { board[it * SIZE + col] != 0 }) {
                (0 until SIZE).forEach { toClear += it * SIZE + col }
            }
        }
        if (toClear.isEmpty()) return
        fadeOut(toClear)
    }

    /** Nothing fits, so the whole board fades away and play goes on. */
    private fun startFresh() {
        fadeOut(board.indices.filter { board[it] != 0 }.toSet())
        dealTray()
    }

    private fun fadeOut(cells: Set<Int>) {
        fadeAnimator?.cancel()
        fadingCells.fill(0)
        for (index in cells) {
            fadingCells[index] = board[index]
            board[index] = 0
        }
        fadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = FADE_MS
            addUpdateListener {
                fadeAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dealTray() {
        for (slot in 0 until TRAY_SLOTS) {
            tray[slot] = Random.nextInt(SHAPES.size)
            trayColors[slot] = Random.nextInt(1, blockColors.size)
        }
    }

    private fun fits(shape: Array<IntArray>, row: Int, col: Int): Boolean {
        for (part in shape) {
            val r = row + part[0]
            val c = col + part[1]
            if (r !in 0 until SIZE || c !in 0 until SIZE) return false
            if (board[r * SIZE + c] != 0) return false
        }
        return true
    }

    private fun fitsAnywhere(shape: Array<IntArray>): Boolean {
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                if (fits(shape, row, col)) return true
            }
        }
        return false
    }

    private fun slotCenter(slot: Int): Pair<Float, Float> {
        val slotWidth = width / TRAY_SLOTS.toFloat()
        val trayHeight = height - trayTop
        return (slotWidth * slot + slotWidth / 2f) to (trayTop + trayHeight / 2f)
    }

    private fun slotAt(x: Float, y: Float): Int {
        if (y < trayTop - cell) return -1
        val slot = (x / (width / TRAY_SLOTS.toFloat())).toInt()
        return slot.coerceIn(0, TRAY_SLOTS - 1)
    }

    private fun shapeWidth(shape: Array<IntArray>) = shape.maxOf { it[1] } + 1
    private fun shapeHeight(shape: Array<IntArray>) = shape.maxOf { it[0] } + 1

    private fun withAlpha(color: Int, alpha: Float) =
        ColorUtils.setAlphaComponent(color, (alpha * 255).roundToInt().coerceIn(0, 255))

    fun saveState(): IntArray = board + tray + trayColors

    fun restoreState(state: IntArray?) {
        if (state == null || state.size != board.size + TRAY_SLOTS * 2) return
        state.copyInto(board, 0, 0, board.size)
        state.copyInto(tray, 0, board.size, board.size + TRAY_SLOTS)
        state.copyInto(trayColors, 0, board.size + TRAY_SLOTS, state.size)
        if (tray.any { it >= SHAPES.size } || trayColors.any { it !in 1 until blockColors.size }) {
            board.fill(0)
            dealTray()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        fadeAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SIZE = 8
        const val TRAY_SLOTS = 3
        const val FADE_MS = 350L

        /** Each piece is a list of (row, column) cells. */
        val SHAPES: Array<Array<IntArray>> = arrayOf(
            arrayOf(intArrayOf(0, 0)),
            arrayOf(intArrayOf(0, 0), intArrayOf(0, 1)),
            arrayOf(intArrayOf(0, 0), intArrayOf(1, 0)),
            arrayOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 2)),
            arrayOf(intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(2, 0)),
            arrayOf(intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(1, 1)),
            arrayOf(intArrayOf(0, 1), intArrayOf(1, 0), intArrayOf(1, 1)),
            arrayOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(1, 0)),
            arrayOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(1, 1)),
            arrayOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(1, 0), intArrayOf(1, 1)),
            arrayOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(0, 3)),
            arrayOf(intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(2, 0), intArrayOf(3, 0)),
            arrayOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 1)),
            arrayOf(intArrayOf(0, 1), intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(1, 2), intArrayOf(2, 1))
        )
    }
}
