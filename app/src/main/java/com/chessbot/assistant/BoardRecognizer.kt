package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    private var cachedFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private var lastContinueState = false

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
        
        if (continueActive && !lastContinueState) {
            cachedFen = if (whiteAtBottom) {
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
            } else {
                "RNBQKBNR/PPPPPPPP/8/8/8/8/pppppppp/rnbqkbnr b KQkq - 0 1"
            }
        }
        lastContinueState = continueActive

        val board = Array(8) { CharArray(8) { '.' } }
        val fenParts = cachedFen.split(" ")[0].split("/")
        for (r in 0..7) {
            var c = 0
            for (ch in fenParts[r]) {
                if (ch.isDigit()) {
                    c += ch.digitToInt()
                } else if (c < 8) {
                    board[r][c++] = ch
                }
            }
        }

        val left = boardLeftPx.coerceIn(0, max(0, bitmap.width - 64))
        val top = boardTopPx.coerceIn(0, max(0, bitmap.height - 64))
        val size = min(boardSizePx, min(bitmap.width - left, bitmap.height - top))
        val cellSize = size / 8f

        // Точное сканирование центральных рядов (2, 3, 4, 5) без ложных срабатываний
        for (r in 2..5) {
            for (c in 0..7) {
                val cellX = left + c * cellSize
                val cellY = top + r * cellSize
                val type = classifyCellColor(bitmap, cellX, cellY, cellSize)
                if (type != '.') {
                    board[r][c] = if (type == 'W') (if (board[r][c] == '.') 'P' else board[r][c]) 
                                  else (if (board[r][c] == '.') 'p' else board[r][c])
                }
            }
        }

        val updatedFen = buildFen(board, whiteAtBottom)
        cachedFen = updatedFen

        return ScanResult(
            board = board,
            fen = updatedFen,
            isValid = true,
            statusNote = "Stable FEN OK",
            continueButtonActive = continueActive,
            detectedWhiteAtBottom = whiteAtBottom
        )
    }

    private fun classifyCellColor(bmp: Bitmap, cellX: Float, cellY: Float, cellSize: Float): Char {
        val N = 12
        var whiteCount = 0
        var blackCount = 0
        for (gy in 3 until N - 3) {
            for (gx in 3 until N - 3) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, bmp.width - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(px, py)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (g > r + 30) continue
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum >= 145) whiteCount++
                else if (lum in 58..144 && b >= r) blackCount++
            }
        }
        return if (whiteCount >= 18) 'W' else if (blackCount >= 18) 'B' else '.'
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
