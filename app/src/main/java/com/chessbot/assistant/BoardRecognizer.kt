package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    data class ScanResult(
        val board: Array<CharArray>,
        val fen: String,
        val isValid: Boolean,
        val statusNote: String,
        val continueButtonActive: Boolean = false,
        val detectedWhiteAtBottom: Boolean = true
    )

    fun isFrameStable(
        prev: Bitmap?,
        curr: Bitmap,
        topPx: Int,
        sizePx: Int
    ): Boolean {
        if (prev == null || prev.width != curr.width || prev.height != curr.height) return false
        val safeTop = topPx.coerceIn(0, max(0, curr.height - 10))
        val safeSize = min(sizePx, curr.height - safeTop)
        if (safeSize < 80) return true

        var diffCount = 0
        var totalSamples = 0
        val step = max(12, safeSize / 24)

        var y = safeTop + step
        while (y < safeTop + safeSize - step) {
            var x = step
            while (x < curr.width - step) {
                val p1 = prev.getPixel(x, y)
                val p2 = curr.getPixel(x, y)
                val dr = abs(Color.red(p1) - Color.red(p2))
                val dg = abs(Color.green(p1) - Color.green(p2))
                val db = abs(Color.blue(p1) - Color.blue(p2))
                if (dr + dg + db > 45) diffCount++
                totalSamples++
                x += step
            }
            y += step
        }
        return totalSamples == 0 || (diffCount.toFloat() / totalSamples) < 0.045f
    }

    fun recognizeBoard(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int,
        whiteAtBottom: Boolean = true,
        lockedFenState: String = ""
    ): ScanResult {
        val board = Array(8) { CharArray(8) { '.' } }
        val continueActive = detectContinueButton(bitmap)

        val left = boardLeftPx.coerceIn(0, max(0, bitmap.width - 64))
        val top = boardTopPx.coerceIn(0, max(0, bitmap.height - 64))
        val size = min(boardSizePx, min(bitmap.width - left, bitmap.height - top))

        if (size < 120) {
            return ScanResult(board, "", false, "Сетка слишком мала", continueActive, whiteAtBottom)
        }

        val cellSize = size / 8f

        // Базовая расстановка фигур на рядах 0 и 7 (в задачах Duolingo порядок всегда стандартный)
        val backRankWhite = charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
        val backRankBlack = charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')

        for (c in 0 until 8) {
            board[0][c] = backRankBlack[c]
            board[7][c] = backRankWhite[c]
        }

        // Сканируем ряды с 1 по 6 на предмет пешек и сдвигов
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (r == 0 || r == 7) continue // ряды 0 и 7 оставляем базовыми

                val cellX = (left + c * cellSize).toInt()
                val cellY = (top + r * cellSize).toInt()
                val cellW = cellSize.toInt()
                val cellH = cellSize.toInt()

                val piece = classifyCellSimple(bitmap, cellX, cellY, cellW, cellH, r)
                board[r][c] = piece
            }
        }

        val fen = buildFen(board, whiteAtBottom)
        val totalPieces = board.sumOf { row -> row.count { it != '.' } }
        val isValid = totalPieces >= 4

        return ScanResult(
            board = board,
            fen = fen,
            isValid = isValid,
            statusNote = "OK ($totalPieces фиг.)",
            continueButtonActive = continueActive,
            detectedWhiteAtBottom = whiteAtBottom
        )
    }

    private fun classifyCellSimple(
        bmp: Bitmap,
        cellX: Int,
        cellY: Int,
        cellW: Int,
        cellH: Int,
        row: Int
    ): Char {
        val N = 24
        var whitePixels = 0
        var blackPixels = 0
        var totalSamples = 0

        for (gy in 4 until N - 4) {
            for (gx in 4 until N - 4) {
                val px = (cellX + (gx + 0.5f) * cellW / N).toInt().coerceIn(0, bmp.width - 1)
                val py = (cellY + (gy + 0.5f) * cellH / N).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(px, py)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                // Пропускаем зелёные элементы оверлея
                if (g > r + 30 && g > b + 15) continue

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                totalSamples++

                if (lum >= 155) {
                    whitePixels++
                } else if (lum in 65..154 && b >= r + 2) {
                    blackPixels++
                }
            }
        }

        if (totalSamples == 0) return '.'

        val whiteRatio = whitePixels.toFloat() / totalSamples
        val blackRatio = blackPixels.toFloat() / totalSamples

        // Пешки занимают меньше места, чем крупные фигуры
        return if (whiteRatio >= 0.18f) {
            if (row == 1 || row == 6) 'P' else if (whiteRatio > 0.35f) 'Q' else 'P'
        } else if (blackRatio >= 0.18f) {
            if (row == 1 || row == 6) 'p' else if (blackRatio > 0.35f) 'q' else 'p'
        } else {
            '.'
        }
    }

    private fun detectContinueButton(bmp: Bitmap): Boolean {
        val yStart = (bmp.height * 0.85f).toInt()
        val yEnd = (bmp.height * 0.96f).toInt()
        val xStart = (bmp.width * 0.15f).toInt()
        val xEnd = (bmp.width * 0.85f).toInt()

        var greenPixels = 0
        var total = 0
        val stepX = max(8, (xEnd - xStart) / 25)
        val stepY = max(6, (yEnd - yStart) / 8)

        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (g > 170 && g > r + 55 && g > b + 80) {
                    greenPixels++
                }
                total++
                x += stepX
            }
            y += stepY
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
                if (p == '.') {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(p)
                }
            }
            if (empty > 0) sb.append(empty)
        }
        val sideToMove = if (whiteAtBottom) "w" else "b"
        sb.append(" $sideToMove KQkq - 0 1")
        return sb.toString()
    }
}
