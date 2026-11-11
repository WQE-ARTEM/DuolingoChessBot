package com.chessbot.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class BoardOverlayView(context: Context) : View(context) {

    var boardLeftPx: Float = 0f
    var boardTopPx: Float = 500f
    var boardSizePx: Float = 1080f
    var whiteAtBottom: Boolean = true
    var showCalibrationGrid: Boolean = true

    var detectedBoard: Array<CharArray>? = null
    var bestMoveUci: String? = null
    var ponderMoveUci: String? = null
    var evalScoreCp: Int = 0

    private val screenLoc = IntArray(2)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 0, 255, 160)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 100)
        textSize = 25f
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
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

    private val ponderArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 165, 0)
        style = Paint.Style.STROKE
        strokeWidth = 9f
        pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
    }

    private val highlightSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(75, 46, 255, 85)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Компенсируем смещение статус-бара/выреза камеры, чтобы сетка 1:1 совпадала с Bitmap MediaProjection
        getLocationOnScreen(screenLoc)
        canvas.save()
        canvas.translate(-screenLoc[0].toFloat(), -screenLoc[1].toFloat())

        val cellSize = boardSizePx / 8f

        if (showCalibrationGrid) {
            canvas.drawRect(boardLeftPx, boardTopPx, boardLeftPx + boardSizePx, boardTopPx + boardSizePx, borderPaint)
            for (i in 1..7) {
                val offset = i * cellSize
                canvas.drawLine(boardLeftPx + offset, boardTopPx, boardLeftPx + offset, boardTopPx + boardSizePx, gridPaint)
                canvas.drawLine(boardLeftPx, boardTopPx + offset, boardLeftPx + boardSizePx, boardTopPx + offset, gridPaint)
            }

            detectedBoard?.let { grid ->
                for (r in 0..7) {
                    for (c in 0..7) {
                        val p = grid[r][c]
                        if (p != '.') {
                            val tag = if (p.isUpperCase()) "w$p" else "b${p.uppercaseChar()}"
                            canvas.drawText(
                                tag,
                                boardLeftPx + c * cellSize + 6f,
                                boardTopPx + r * cellSize + 24f,
                                labelPaint
                            )
                        }
                    }
                }
            }
        }

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

        ponderMoveUci?.let { uci ->
            if (uci.length >= 4) drawMoveArrow(canvas, uci, cellSize, isBest = false)
        }

        bestMoveUci?.let { uci ->
            if (uci.length >= 4) drawMoveArrow(canvas, uci, cellSize, isBest = true)
        }

        canvas.restore()
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

        val x1 = boardLeftPx + (c1 + 0.5f) * cellSize
        val y1 = boardTopPx + (r1 + 0.5f) * cellSize
        val x2 = boardLeftPx + (c2 + 0.5f) * cellSize
        val y2 = boardTopPx + (r2 + 0.5f) * cellSize

        if (isBest) {
            canvas.drawRect(
                boardLeftPx + c2 * cellSize,
                boardTopPx + r2 * cellSize,
                boardLeftPx + (c2 + 1) * cellSize,
                boardTopPx + (r2 + 1) * cellSize,
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
        path.lineTo(
            (x2 - size * cos(angle - Math.PI / 6)).toFloat(),
            (y2 - size * sin(angle - Math.PI / 6)).toFloat()
        )
        path.lineTo(
            (x2 - size * cos(angle + Math.PI / 6)).toFloat(),
            (y2 - size * sin(angle + Math.PI / 6)).toFloat()
        )
        path.close()
        canvas.drawPath(path, paint)
    }
}
