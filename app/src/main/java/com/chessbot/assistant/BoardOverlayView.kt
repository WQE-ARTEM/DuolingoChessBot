package com.chessbot.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class BoardOverlayView(context: Context) : View(context) {

    var boardLeftPx: Float = 0f
    var boardTopPx: Float = 500f
    var boardSizePx: Float = 1080f
    var whiteAtBottom: Boolean = false
    var showCalibrationGrid: Boolean = true
    var isManualMode: Boolean = false

    var detectedBoard: Array<CharArray>? = null
    var bestMoveUci: String? = null
    var ponderMoveUci: String? = null
    var evalScoreCp: Int = 0

    var onSquareSelectedListener: ((Int, Int) -> Unit)? = null
    var selectedSquare: Pair<Int, Int>? = null

    private val screenLoc = IntArray(2)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 160)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 255, 255)
        textSize = 36f
        isFakeBoldText = true
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 72f
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private val bestArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 46, 255, 85)
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }

    private val bestHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 46, 255, 85)
        style = Paint.Style.FILL
    }

    private val highlightSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 46, 255, 85)
        style = Paint.Style.FILL
    }

    private val selectedSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 215, 0)
        style = Paint.Style.FILL
    }

    private val evalBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 35, 45, 50)
        style = Paint.Style.FILL
    }

    private val evalWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 235, 245, 255)
        style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isManualMode) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN) {
            getLocationOnScreen(screenLoc)
            val touchX = event.rawX - screenLoc[0]
            val touchY = event.rawY - screenLoc[1]

            val cellSize = boardSizePx / 8f
            if (touchX in boardLeftPx..(boardLeftPx + boardSizePx) &&
                touchY in boardTopPx..(boardTopPx + boardSizePx)) {
                
                val c = ((touchX - boardLeftPx) / cellSize).toInt().coerceIn(0, 7)
                val r = ((touchY - boardTopPx) / cellSize).toInt().coerceIn(0, 7)
                
                val file = if (whiteAtBottom) c else 7 - c
                val rank = if (whiteAtBottom) 7 - r else r
                
                onSquareSelectedListener?.invoke(file, rank)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(screenLoc)
        canvas.save()
        canvas.translate(-screenLoc[0].toFloat(), -screenLoc[1].toFloat())

        val cellSize = boardSizePx / 8f

        if (showCalibrationGrid || isManualMode) {
            canvas.drawRect(boardLeftPx, boardTopPx, boardLeftPx + boardSizePx, boardTopPx + boardSizePx, borderPaint)
            for (i in 1..7) {
                val offset = i * cellSize
                canvas.drawLine(boardLeftPx + offset, boardTopPx, boardLeftPx + offset, boardTopPx + boardSizePx, gridPaint)
                canvas.drawLine(boardLeftPx, boardTopPx + offset, boardLeftPx + boardSizePx, boardTopPx + offset, gridPaint)
            }

            // Рендер выделенной и выбранной клетки
            selectedSquare?.let { (f, r) ->
                val c = if (whiteAtBottom) f else 7 - f
                val rankIdx = if (whiteAtBottom) 7 - r else r
                canvas.drawRect(
                    boardLeftPx + c * cellSize,
                    boardTopPx + rankIdx * cellSize,
                    boardLeftPx + (c + 1) * cellSize,
                    boardTopPx + (rankIdx + 1) * cellSize,
                    selectedSquarePaint
                )
            }

            // Отрисовка фигур шахматными иконками
            detectedBoard?.let { grid ->
                for (r in 0..7) {
                    for (c in 0..7) {
                        val p = grid[r][c]
                        if (p != '.') {
                            val icon = getPieceIcon(p)
                            val x = boardLeftPx + (c + 0.5f) * cellSize
                            val y = boardTopPx + (r + 0.7f) * cellSize
                            
                            // Цвет иконки: белые светлые с черной обводкой, черные темные
                            if (p.isUpperCase()) {
                                piecePaint.color = Color.WHITE
                                piecePaint.style = Paint.Style.FILL
                            } else {
                                piecePaint.color = Color.rgb(20, 30, 35)
                                piecePaint.style = Paint.Style.FILL
                            }
                            canvas.drawText(icon, x, y, piecePaint)
                        }
                    }
                }
            }

            // Буквы и цифры координат
            for (i in 0..7) {
                val fileChar = if (whiteAtBottom) ('a' + i) else ('h' - i)
                val rankChar = if (whiteAtBottom) ('8' - i) else ('1' + i)
                canvas.drawText(fileChar.toString(), boardLeftPx + i * cellSize + 10f, boardTopPx + boardSizePx - 10f, labelPaint)
                canvas.drawText(rankChar.toString(), boardLeftPx + 10f, boardTopPx + i * cellSize + 35f, labelPaint)
            }
        }

        // Индикатор оценки
        val barWidth = 14f
        val barLeft = (boardLeftPx + 4f).coerceAtLeast(2f)
        canvas.drawRect(barLeft, boardTopPx, barLeft + barWidth, boardTopPx + boardSizePx, evalBgPaint)
        val clampedEval = evalScoreCp.coerceIn(-1000, 1000)
        val whiteFraction = (0.5f + clampedEval / 2000f).coerceIn(0.04f, 0.96f)
        val whiteHeight = boardSizePx * whiteFraction
        canvas.drawRect(
            barLeft,
            boardTopPx + boardSizePx - whiteHeight,
            barLeft + barWidth,
            boardTopPx + boardSizePx,
            evalWhitePaint
        )

        bestMoveUci?.let { uci ->
            if (uci.length >= 4) drawMoveArrow(canvas, uci, cellSize)
        }

        canvas.restore()
    }

    private fun getPieceIcon(p: Char): String = when (p) {
        'K' -> "♔"; 'Q' -> "♕"; 'R' -> "♖"; 'B' -> "♗"; 'N' -> "♘"; 'P' -> "♙"
        'k' -> "♚"; 'q' -> "♛"; 'r' -> "♜"; 'b' -> "♝"; 'n' -> "♞"; 'p' -> "♟"
        else -> ""
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

        val x1 = boardLeftPx + (c1 + 0.5f) * cellSize
        val y1 = boardTopPx + (r1 + 0.5f) * cellSize
        val x2 = boardLeftPx + (c2 + 0.5f) * cellSize
        val y2 = boardTopPx + (r2 + 0.5f) * cellSize

        canvas.drawRect(
            boardLeftPx + c2 * cellSize,
            boardTopPx + r2 * cellSize,
            boardLeftPx + (c2 + 1) * cellSize,
            boardTopPx + (r2 + 1) * cellSize,
            highlightSquarePaint
        )
        canvas.drawLine(x1, y1, x2, y2, bestArrowPaint)
        drawArrowHead(canvas, x1, y1, x2, y2, 36f, bestHeadPaint)
    }

    private fun drawArrowHead(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, size: Float, paint: Paint) {
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val path = Path()
        path.moveTo(x2, y2)
        path.lineTo((x2 - size * cos(angle - Math.PI / 6)).toFloat(), (y2 - size * sin(angle - Math.PI / 6)).toFloat())
        path.lineTo((x2 - size * cos(angle + Math.PI / 6)).toFloat(), (y2 - size * sin(angle + Math.PI / 6)).toFloat())
        path.close()
        canvas.drawPath(path, paint)
    }
}
