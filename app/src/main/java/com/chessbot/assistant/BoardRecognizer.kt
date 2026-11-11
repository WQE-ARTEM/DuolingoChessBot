package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    // Полная матрица состояния доски по памяти (начиная со стандартной расстановки)
    private var boardState = arrayOf(
        charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
        charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
        charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
    )
    private var lastContinueState = false
    private var initialized = false

    data class ScanResult(
        val board: Array<CharArray>,
        val fen: String,
        val isValid: Boolean,
        val statusNote: String,
        val continueButtonActive: Boolean = false,
        val detectedWhiteAtBottom: Boolean = true
    )

    fun isFrameStable(prev: Bitmap?, curr: Bitmap, topPx: Int, sizePx: Int): Boolean {
        return true
    }

    fun recognizeBoard(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int,
        whiteAtBottom: Boolean = true,
        lockedFenState: String = ""
    ): ScanResult {
        val continueActive = detectContinueButton(bitmap)

        // Если появилась кнопка продолжения или это старт — сбрасываем доску в исходную позицию
        if (continueActive && !lastContinueState || !initialized) {
            boardState = arrayOf(
                charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
                charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
                charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
            )
            initialized = true
        }
        lastContinueState = continueActive

        val left = boardLeftPx.coerceIn(0, max(0, bitmap.width - 64))
        val top = boardTopPx.coerceIn(0, max(0, bitmap.height - 64))
        val size = min(boardSizePx, min(bitmap.width - left, bitmap.height - top))
        val cellSize = size / 8f

        // Сканируем поле по памяти: ищем, где исчезла фигура и куда она переместилась
        trackPieceMovements(bitmap, left, top, cellSize)

        val fen = buildFen(boardState, whiteAtBottom)

        return ScanResult(
            board = boardState,
            fen = fen,
            isValid = true,
            statusNote = "Memory Tracker OK",
            continueButtonActive = continueActive,
            detectedWhiteAtBottom = whiteAtBottom
        )
    }

    private fun trackPieceMovements(bmp: Bitmap, left: Int, top: Int, cellSize: Float) {
        // Проверяем каждую клетку доски: есть ли на ней реальный объект (фигура/пешка)
        val currentPresence = Array(8) { r ->
            BooleanArray(8) { c ->
                val cellX = left + c * cellSize
                val cellY = top + r * cellSize
                hasVisualObject(bmp, cellX, cellY, cellSize)
            }
        }

        // Ищем клетку, которая опустела, и клетку, которая заполнилась
        var emptyFrom: Pair<Int, Int>? = null
        var filledTo: Pair<Int, Int>? = null

        for (r in 0..7) {
            for (c in 0..7) {
                val hadPiece = boardState[r][c] != '.'
                val hasVisual = currentPresence[r][c]

                if (hadPiece && !hasVisual) {
                    emptyFrom = r to c
                } else if (!hadPiece && hasVisual) {
                    filledTo = r to c
                }
            }
        }

        // Если зафиксировано перемещение (кто-то ушел и кто-то пришел)
        if (emptyFrom != null && filledTo != null) {
            val piece = boardState[emptyFrom.first][emptyFrom.second]
            boardState[emptyFrom.first][emptyFrom.second] = '.'
            boardState[filledTo.first][filledTo.second] = piece
        }
    }

    private fun hasVisualObject(bmp: Bitmap, cellX: Float, cellY: Float, cellSize: Float): Boolean {
        val N = 10
        var pixels = 0
        for (gy in 2 until N - 2) {
            for (gx in 2 until N - 2) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, bmp.width - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(px, py)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (g > r + 30) continue // зелёная стрелка
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum >= 135 || (lum in 50..134 && b >= r)) {
                    pixels++
                }
            }
        }
        return pixels >= 8 // Если на клетке есть хотя бы немного пикселей фигуры
    }

    private fun detectContinueButton(bmp: Bitmap): Boolean {
        val yStart = (bmp.height * 0.85f).toInt()
        val yEnd = (bmp.height * 0.96f).toInt()
        val xStart = (bmp.width * 0.15f).toInt()
        val xEnd = (bmp.width * 0.85f).toInt()
        var greenPixels = 0; var total = 0
        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (g > 170 && g > r + 55) greenPixels++
                total++; x += 15
            }
            y += 10
        }
        return total > 0 && (greenPixels.toFloat() / total) > 0.35f
    }

    private fun buildFen(board: Array<CharArray>, whiteAtBottom: Boolean): String {
        val sb = StringBuilder()
        val rows = if (whiteAtBottom) (0..7) else (7 downTo 0)
        val cols = if (whiteAtBottom) (0..7) else (7 downTo 0)
        var firstRow = true
        for (r in rows) {
            if (!firstRow) sb.append('/')
            firstRow = false
            var empty = 0
            for (c in cols) {
                val p = board[r][c]
                if (p == '.') empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(p)
                }
            }
            if (empty > 0) sb.append(empty)
        }
        sb.append(if (whiteAtBottom) " w KQkq - 0 1" else " b KQkq - 0 1")
        return sb.toString()
    }
}
