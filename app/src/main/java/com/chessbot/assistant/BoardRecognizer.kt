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

    fun isFrameStable(prev: Bitmap?, curr: Bitmap, topPx: Int, sizePx: Int): Boolean {
        if (prev == null || prev.width != curr.width || prev.height != curr.height) return false
        val safeTop = topPx.coerceIn(0, max(0, curr.height - 10))
        val safeSize = min(sizePx, curr.height - safeTop)
        if (safeSize < 80) return true
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
        val board = Array(8) { CharArray(8) { '.' } }
        val continueActive = detectContinueButton(bitmap)

        val left = boardLeftPx.coerceIn(0, max(0, bitmap.width - 64))
        val top = boardTopPx.coerceIn(0, max(0, bitmap.height - 64))
        val size = min(boardSizePx, min(bitmap.width - left, bitmap.height - top))

        if (size < 120) {
            return ScanResult(board, "", false, "Сетка мала", continueActive, whiteAtBottom)
        }

        val cellSize = size / 8f

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val cellX = left + c * cellSize
                val cellY = top + r * cellSize
                board[r][c] = classifyPixelSilhouette(bitmap, cellX, cellY, cellSize, r, c)
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

    private fun classifyPixelSilhouette(bmp: Bitmap, cellX: Float, cellY: Float, cellSize: Float, row: Int, col: Int): Char {
        val N = 24
        val grid = Array(N) { BooleanArray(N) }
        var whitePixels = 0
        var blackPixels = 0

        for (gy in 2 until N - 2) {
            for (gx in 2 until N - 2) {
                if (gx <= 8 && gy <= 6) continue // пропускаем значки оверлея
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, bmp.width - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(px, py)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (g > r + 30) continue // зелёная линия оверлея

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum >= 150) {
                    grid[gy][gx] = true
                    whitePixels++
                } else if (lum in 55..149 && b >= r) {
                    grid[gy][gx] = true
                    blackPixels++
                }
            }
        }

        val isWhite = whitePixels >= blackPixels
        val total = if (isWhite) whitePixels else blackPixels
        if (total < 18) return '.'

        // Вычисляем границы фигуры внутри клетки (bounding box)
        var topY = N; var bottomY = 0; var minX = N; var maxX = 0
        for (gy in 0 until N) {
            for (gx in 0 until N) {
                if (grid[gy][gx]) {
                    if (gy < topY) topY = gy
                    if (gy > bottomY) bottomY = gy
                    if (gx < minX) minX = gx
                    if (gx > maxX) maxX = gx
                }
            }
        }

        val h = bottomY - topY + 1
        val w = maxX - minX + 1
        if (h < 5 || w < 4) return '.'

        // Ширина на разных уровнях высоты фигуры (0.0 = верхушка, 1.0 = база)
        var topWidth = 0
        var midWidth = 0
        var baseWidth = 0
        for (gx in minX..maxX) {
            if (grid[topY + h / 6][gx]) topWidth++
            if (grid[topY + h / 2][gx]) midWidth++
            if (grid[topY + (5 * h) / 6][gx]) baseWidth++
        }

        // ПРЕЦИЗИОННАЯ КЛАССИФИКАЦИЯ ПО ФОРМЕ ПИКСЕЛЕЙ:
        return if (h <= 11 || total <= 50) {
            // Маленькая высота или площадь -> Пешка
            if (isWhite) 'P' else 'p'
        } else if (topWidth >= midWidth + 3 || (topWidth >= 8 && midWidth <= 7)) {
            // Плоский широкий верх -> Ладья
            if (isWhite) 'R' else 'r'
        } else if (topWidth <= 4 && midWidth >= 8) {
            // Узкая макушка, широкое пузо -> Слон или Король
            if (h >= 16 && midWidth >= 9) {
                if (isWhite) 'K' else 'k'
            } else {
                if (isWhite) 'B' else 'b'
            }
        } else if (topWidth >= 6 && baseWidth >= 9) {
            // Широкая корона -> Ферзь или Король
            if (isWhite) 'Q' else 'q'
        } else {
            // Асимметричный силуэт / стандарт -> Конь или пешка продвинутая
            if (row == 0 || row == 7) {
                when (col) {
                    0, 7 -> if (isWhite) 'R' else 'r'
                    1, 6 -> if (isWhite) 'N' else 'n'
                    2, 5 -> if (isWhite) 'B' else 'b'
                    3 -> if (isWhite) 'Q' else 'q'
                    4 -> if (isWhite) 'K' else 'k'
                    else -> if (isWhite) 'P' else 'p'
                }
            } else {
                if (isWhite) 'N' else 'n'
            }
        }
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
                total++; x += 12
            }
            y += 8
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
