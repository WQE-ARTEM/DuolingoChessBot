package com.chessbot.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 0, 255, 160)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 215, 0)
        style = Paint.Style.FILL
    }

    private val lastMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 200, 255)
        style = Paint.Style.FILL
    }

    private val highlightSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 46, 255, 85)
        style = Paint.Style.FILL
    }

    private val whiteBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 240, 248, 255)
        style = Paint.Style.FILL
    }

    private val blackBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 22, 32, 42)
        style = Paint.Style.FILL
    }

    private val badgeStrokeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 30, 40)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val badgeStrokeBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 210, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val pieceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 120)
        textSize = 23f
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val bestArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 46, 255, 85)
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }

    private val bestHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 46, 255, 85)
        style = Paint.Style.FILL
    }

    private val ponderArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 165, 0)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
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

            lastMovedCells?.let { (from, to) ->
                drawCellRect(canvas, from.first, from.second, cellSize, lastMovePaint)
                drawCellRect(canvas, to.first, to.second, cellSize, lastMovePaint)
            }

            selectedBoardCell?.let { (r, c) ->
                drawCellRect(canvas, r, c, cellSize, selectedPaint)
            }

            // Рисуем значки фигур в нижней части каждой клетки, чтобы они попадали ровно на базу фигуры
            detectedBoard?.let { grid ->
                for (r in 0..7) {
                    for (c in 0..7) {
                        val p = grid[r][c]
                        if (p == '.') continue

                        val sc = if (whiteAtBottom) c else 7 - c
                        val sr = if (whiteAtBottom) r else 7 - r

                        val cx = (sc + 0.5f) * cellSize
                        val cy = (sr + 0.72f) * cellSize

                        val bw = cellSize * 0.66f
                        val bh = cellSize * 0.35f
                        badgeRect.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)

                        val isW = p.isUpperCase()
                        canvas.drawRoundRect(badgeRect, 10f, 10f, if (isW) whiteBadgeBg else blackBadgeBg)
                        canvas.drawRoundRect(badgeRect, 10f, 10f, if (isW) badgeStrokeWhite else badgeStrokeBlack)

                        pieceTextPaint.color = if (isW) Color.rgb(15, 25, 35) else Color.rgb(235, 245, 255)
                        pieceTextPaint.textSize = cellSize * 0.24f
                        val label = getPieceSymbolAndLetter(p)
                        canvas.drawText(label, cx, cy + pieceTextPaint.textSize * 0.36f, pieceTextPaint)
                    }
                }
            }

            for (i in 0..7) {
                val fileChar = if (whiteAtBottom) ('a' + i) else ('h' - i)
                val rankChar = if (whiteAtBottom) ('8' - i) else ('1' + i)
                canvas.drawText(
                    fileChar.toString(),
                    i * cellSize + cellSize - 20f,
                    size - 6f,
                    coordPaint
                )
                canvas.drawText(
                    rankChar.toString(),
                    5f,
                    i * cellSize + 22f,
                    coordPaint
                )
            }
        }

        ponderMoveUci?.let { uci ->
            if (uci.length >= 4) drawMoveArrow(canvas, uci, cellSize, isBest = false)
        }
        bestMoveUci?.let { uci ->
            if (uci.length >= 4) drawMoveArrow(canvas, uci, cellSize, isBest = true)
        }
    }

    private fun drawCellRect(canvas: Canvas, boardR: Int, boardC: Int, cellSize: Float, paint: Paint) {
        val sc = if (whiteAtBottom) boardC else 7 - boardC
        val sr = if (whiteAtBottom) boardR else 7 - boardR
        canvas.drawRect(
            sc * cellSize,
            sr * cellSize,
            (sc + 1) * cellSize,
            (sr + 1) * cellSize,
            paint
        )
    }

    private fun getPieceSymbolAndLetter(p: Char): String = when (p) {
        'K' -> "♔K"; 'Q' -> "♕Q"; 'R' -> "♖R"; 'B' -> "♗B"; 'N' -> "♘N"; 'P' -> "♙P"
        'k' -> "♚k"; 'q' -> "♛q"; 'r' -> "♜r"; 'b' -> "♝b"; 'n' -> "♞n"; 'p' -> "♟p"
        else -> ""
    }

    private fun drawMoveArrow(canvas: Canvas, uci: String, cellSize: Float, isBest: Boolean) {
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

        if (isBest) {
            canvas.drawRect(
                c2 * cellSize,
                r2 * cellSize,
                (c2 + 1) * cellSize,
                (r2 + 1) * cellSize,
                highlightSquarePaint
            )
            canvas.drawLine(x1, y1, x2, y2, bestArrowPaint)
            drawArrowHead(canvas, x1, y1, x2, y2, 36f, bestHeadPaint)
        } else {
            canvas.drawLine(x1, y1, x2, y2, ponderArrowPaint)
        }
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
