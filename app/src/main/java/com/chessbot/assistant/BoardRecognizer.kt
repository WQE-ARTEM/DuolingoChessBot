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
        val kingScore = Array(8) { IntArray(8) { -1000 } }

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val cellX = (left + c * cellSize).toInt()
                val cellY = (top + r * cellSize).toInt()
                val cellW = cellSize.toInt()
                val cellH = cellSize.toInt()

                val res = classifyCell(bitmap, cellX, cellY, cellW, cellH, r, c)
                board[r][c] = res.first
                kingScore[r][c] = res.second
            }
        }

        // Проверяем ориентацию доски по положению королей
        var whiteKingRow = -1
        var blackKingRow = -1
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (board[r][c] == 'K') whiteKingRow = r
                if (board[r][c] == 'k') blackKingRow = r
            }
        }
        val detectedWhiteBottom = if (whiteKingRow != -1 && blackKingRow != -1) {
            whiteKingRow > blackKingRow
        } else {
            whiteAtBottom
        }

        enforceSingleKing(board, kingScore, isWhite = true)
        enforceSingleKing(board, kingScore, isWhite = false)

        var totalPieces = 0
        var whiteKing = false
        var blackKing = false
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val p = board[r][c]
                if (p != '.') totalPieces++
                if (p == 'K') whiteKing = true
                if (p == 'k') blackKing = true
            }
        }

        val isValid = whiteKing && blackKing && totalPieces >= 2
        val fen = buildFen(board, detectedWhiteBottom)

        return ScanResult(
            board = board,
            fen = fen,
            isValid = isValid,
            statusNote = if (isValid) "OK ($totalPieces фиг.)" else "Поиск королей...",
            continueButtonActive = continueActive,
            detectedWhiteAtBottom = detectedWhiteBottom
        )
    }

    private fun classifyCell(
        bmp: Bitmap,
        cellX: Int,
        cellY: Int,
        cellW: Int,
        cellH: Int,
        row: Int,
        col: Int
    ): Pair<Char, Int> {
        val N = 32
        var whitePixels = 0
        var blackPixels = 0
        var totalSamples = 0
        var bgLumSum = 0

        for (gy in 3 until N - 3) {
            for (gx in 3 until N - 3) {
                if (gx <= 9 && gy <= 7) continue // пропускаем метки оверлея
                val px = (cellX + (gx + 0.5f) * cellW / N).toInt().coerceIn(0, bmp.width - 1)
                val py = (cellY + (gy + 0.5f) * cellH / N).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(px, py)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                if (g > r + 35 && g > b + 20) continue // зелёная стрелка

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                totalSamples++

                if (lum >= 155) {
                    whitePixels++
                } else if (lum in 62..154 && b >= r + 2) {
                    blackPixels++
                } else {
                    bgLumSum += lum
                }
            }
        }

        if (totalSamples == 0) return '.' to -1000

        val isWhite: Boolean
        val density: Float
        if (whitePixels >= 42 && whitePixels >= blackPixels) {
            isWhite = true
            density = whitePixels.toFloat() / totalSamples
        } else if (blackPixels >= 38) {
            isWhite = false
            density = blackPixels.toFloat() / totalSamples
        } else {
            return '.' to -1000
        }

        // Определяем тип фигуры по ряду и плотности пикселей
        val pieceChar = when {
            density < 0.22f -> if (isWhite) 'P' else 'p' // Пешка
            row == 0 || row == 7 -> {
                // Базовый задний ряд Duolingo
                when (col) {
                    0, 7 -> if (isWhite) 'R' else 'r'
                    1, 6 -> if (isWhite) 'N' else 'n'
                    2, 5 -> if (isWhite) 'B' else 'b'
                    3 -> if (isWhite) 'Q' else 'q'
                    4 -> if (isWhite) 'K' else 'k'
                    else -> if (isWhite) 'P' else 'p'
                }
            }
            density > 0.42f -> if (isWhite) 'Q' else 'q'
            else -> if (isWhite) 'N' else 'n'
        }

        val score = if (pieceChar.lowercaseChar() == 'k') 100 else (density * 100).toInt()
        return pieceChar to score
    }

    private fun enforceSingleKing(board: Array<CharArray>, kingScore: Array<IntArray>, isWhite: Boolean) {
        val target = if (isWhite) 'K' else 'k'
        val coords = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (board[r][c] == target) coords.add(r to c)
            }
        }
        if (coords.size > 1) {
            val best = coords.maxByOrNull { (r, c) -> kingScore[r][c] } ?: coords[0]
            for (pos in coords) {
                if (pos != best) board[pos.first][pos.second] = if (isWhite) 'Q' else 'q'
            }
        }
    }

    private fun detectContinueButton(bmp: Bitmap): Boolean {
        val yStart = (bmp.height * 0.85f).toInt()
        val yEnd = (bmp.height * 0.96f).toInt()
        val xStart = (bmp.width * 0.15f).toInt()
        val xEnd = (bmp.width * 0.85f).toInt()

        var greenPixels = 0
        var total = 0
        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (g > 170 && g > r + 55 && g > b + 80) greenPixels++
                total++
                x += max(8, (xEnd - xStart) / 25)
            }
            y += max(6, (yEnd - yStart) / 8)
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
