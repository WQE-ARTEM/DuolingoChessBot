package com.chessbot.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class BoardOverlayView(context: Context) : View(context) {

    var boardLeftPx: Float = 0f
    var boardTopPx: Float = 500f
    var boardSizePx: Float = 1080f
    var whiteAtBottom: Boolean = true
    var showBoardOverlay: Boolean = true
    var touchInteractive: Boolean = false

    var detectedBoard: Array<CharArray>? = null
    var bestMoveUci: String? = null
    var ponderMoveUci: String? = null
    var evalScoreCp: Int = 0
    var selectedBoardCell: Pair<Int, Int>? = null
    var lastMovedCells: Pair<Pair<Int, Int>, Pair<Int, Int>>? = null

    var onBoardCellClick: ((Int, Int) -> Unit)? = null

    private val badgeRect = RectF()

    // Тонкая фиолетово-золотая сетка (не пересекается по спектру с голубыми фигурами Duolingo)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 200, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 46, 255, 120)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val whiteBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 250, 250, 240)
        style = Paint.Style.FILL
    }

    private val blackBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 28, 22, 38)
        style = Paint.Style.FILL
    }

    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 195, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val pieceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val bestArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 46, 255, 85)
        style = Paint.Style.STROKE
        strokeWidth = 11f
        strokeCap = Paint.Cap.ROUND
    }

    private val bestHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 46, 255, 85)
        style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchInteractive) return false
        if (event.action == MotionEvent.ACTION_DOWN) {
            val w = width.toFloat().coerceAtLeast(1f)
            val x = event.x
            val y = event.y
            if (x in 0f..w && y in 0f..w) {
                val cellSize = w / 8f
                val screenCol = (x / cellSize).toInt().coerceIn(0, 7)
                val screenRow = (y / cellSize).toInt().coerceIn(0, 7)
                val boardRow = if (whiteAtBottom) screenRow else 7 - screenRow
                val boardCol = if (whiteAtBottom) screenCol else 7 - screenCol
                onBoardCellClick?.invoke(boardRow, boardCol)
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = width.toFloat().coerceAtLeast(boardSizePx)
        val cellSize = size / 8f

        if (showBoardOverlay) {
            canvas.drawRect(0f, 0f, size, size, borderPaint)

            for (i in 1..7) {
                val off = i * cellSize
                canvas.drawLine(off, 0f, off, size, gridPaint)
                canvas.drawLine(0f, off, size, off, gridPaint)
            }

            // Компактная метка строго в ЛЕВОМ ВЕРХНЕМ уголке (0..26% клетки) — центр и низ фигуры на 100% свободны!
            detectedBoard?.let { grid ->
                for (r in 0..7) {
                    for (c in 0..7) {
                        val p = grid[r][c]
                        if (p == '.') continue

                        val sc = if (whiteAtBottom) c else 7 - c
                        val sr = if (whiteAtBottom) r else 7 - r

                        val bw = cellSize * 0.34f
                        val bh = cellSize * 0.24f
                        val left = sc * cellSize + 2f
                        val top = sr * cellSize + 2f
                        badgeRect.set(left, top, left + bw, top + bh)

                        val isW = p.isUpperCase()
                        canvas.drawRoundRect(badgeRect, 6f, 6f, if (isW) whiteBadgeBg else blackBadgeBg)
                        canvas.drawRoundRect(badgeRect, 6f, 6f, badgeStroke)

                        pieceTextPaint.color = if (isW) Color.rgb(15, 20, 30) else Color.rgb(255, 230, 130)
                        pieceTextPaint.textSize = cellSize * 0.17f
                        canvas.drawText(
                            p.toString(),
                            badgeRect.centerX(),
                            badgeRect.centerY() + pieceTextPaint.textSize * 0.36f,
                            pieceTextPaint
                        )
                    }
                }
            }
        }

        bestMoveUci?.let { uci ->
            if (uci.length >= 4) drawMoveArrow(canvas, uci, cellSize)
        }
    }

    private fun drawMoveArrow(canvas: Canvas, uci: String, cellSize: Float) {
        val fromFile = uci[0] - 'a'
        val fromRank = uci[1] - '1'
        val toFile = uci[2] - 'a'
        val toRank = uci[3] - '1'

        val c1 = if (whiteAtBottom) fromFile else 7 - fromFile
        val r1 = if (whiteAtBottom) 7 - fromRank else fromRank
        val c2 = if (whiteAtBottom) toFile else 7 - toFile
        val r2 = if (whiteAtBottom) 7 - toRank else toRank

        val x1 = (c1 + 0.5f) * cellSize
        val y1 = (r1 + 0.5f) * cellSize
        val x2 = (c2 + 0.5f) * cellSize
        val y2 = (r2 + 0.5f) * cellSize

        canvas.drawLine(x1, y1, x2, y2, bestArrowPaint)
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val headSize = 32f
        val path = Path().apply {
            moveTo(x2, y2)
            lineTo((x2 - headSize * cos(angle - Math.PI / 6)).toFloat(), (y2 - headSize * sin(angle - Math.PI / 6)).toFloat())
            lineTo((x2 - headSize * cos(angle + Math.PI / 6)).toFloat(), (y2 - headSize * sin(angle + Math.PI / 6)).toFloat())
            close()
        }
        canvas.drawPath(path, bestHeadPaint)
    }
}
