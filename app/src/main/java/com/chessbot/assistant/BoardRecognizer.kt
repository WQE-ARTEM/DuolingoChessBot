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

    // Нормализованные бинарные маски 12x12 силуэтов Duolingo
    // 0 = фон, 1 = тело фигуры
    private val TPL_PAWN = arrayOf(
        "000000000000",
        "000000000000",
        "000011110000",
        "000111111000",
        "000111111000",
        "000011110000",
        "000001100000",
        "000011110000",
        "000111111000",
        "001111111100",
        "011111111110",
        "000000000000"
    )

    private val TPL_KNIGHT = arrayOf(
        "000011000000",
        "000111100000",
        "001111110000",
        "011111111000",
        "011111111100",
        "011000111100",
        "000000111100",
        "000001111100",
        "000011111100",
        "000111111110",
        "001111111110",
        "000000000000"
    )

    private val TPL_BISHOP = arrayOf(
        "000001100000",
        "000011110000",
        "000111111000",
        "001100111100",
        "001100111100",
        "001111111100",
        "000111111000",
        "000011110000",
        "000111111000",
        "001111111100",
        "011111111110",
        "000000000000"
    )

    private val TPL_ROOK = arrayOf(
        "001101101100",
        "001111111100",
        "001100001100",
        "001100001100",
        "000111111000",
        "000111111000",
        "000111111000",
        "000111111000",
        "001111111100",
        "001111111100",
        "011111111110",
        "000000000000"
    )

    private val TPL_QUEEN = arrayOf(
        "010010010010",
        "011011011011",
        "001111111100",
        "001100001100",
        "000111111000",
        "000111111000",
        "000011110000",
        "000011110000",
        "000111111000",
        "001111111100",
        "011111111110",
        "000000000000"
    )

    private val TPL_KING = arrayOf(
        "000001100000",
        "000011110000",
        "000001100000",
        "001111111100",
        "011110011110",
        "011111111110",
        "001111111100",
        "000111111000",
        "000111111000",
        "001111111100",
        "011111111110",
        "000000000000"
    )

    fun isFrameStable(prev: Bitmap?, curr: Bitmap, topPx: Int, sizePx: Int): Boolean = true

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

        val boardPixels = IntArray(size * size)
        bitmap.getPixels(boardPixels, 0, size, left, top, size, size)
        val cellSize = size / 8f

        data class Detected(val r: Int, val c: Int, val isWhite: Boolean, val type: Char, val kScore: Int)
        val detected = mutableListOf<Detected>()

        for (r in 0..7) {
            for (c in 0..7) {
                val piece = extractAndMatchCell(boardPixels, size, c * cellSize, r * cellSize, cellSize, r, c)
                if (piece != null) {
                    detected.add(Detected(r, c, piece.first, piece.second, piece.third))
                }
            }
        }

        // Авто-определение стороны (Белые или Чёрные снизу)
        var whiteBottomVotes = 0
        var blackBottomVotes = 0
        for (d in detected) {
            if (d.isWhite) {
                if (d.r >= 4) whiteBottomVotes += 2 else blackBottomVotes++
            } else {
                if (d.r <= 3) whiteBottomVotes += 2 else blackBottomVotes++
            }
        }
        val isWhiteBottom = if (whiteBottomVotes != blackBottomVotes) whiteBottomVotes > blackBottomVotes else whiteAtBottom

        // Гарантируем ровно одного короля на каждую сторону
        for (sideWhite in listOf(true, false)) {
            val sideList = detected.filter { it.isWhite == sideWhite }
            if (sideList.isEmpty()) continue

            val kingCand = sideList.maxByOrNull { it.kScore }
            for (d in sideList) {
                var ch = d.type
                if (d == kingCand) {
                    ch = 'k'
                } else if (ch == 'k') {
                    ch = 'b' // если не главный король — это слон
                }
                board[d.r][d.c] = if (sideWhite) ch.uppercaseChar() else ch
            }
        }

        val fen = buildFen(board, isWhiteBottom)
        val sideName = if (isWhiteBottom) "Белые" else "Чёрные"

        return ScanResult(
            board = board,
            fen = fen,
            isValid = detected.size >= 4,
            statusNote = "OK ($sideName, ${detected.size} фиг.)",
            continueButtonActive = continueActive,
            detectedWhiteAtBottom = isWhiteBottom
        )
    }

    private fun extractAndMatchCell(
        boardPixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        row: Int,
        col: Int
    ): Triple<Boolean, Char, Int>? {
        val N = 24
        var bgLumSum = 0
        var bgCount = 0
        val corners = arrayOf(2 to 21, 21 to 21, 21 to 2, 2 to 18, 21 to 18)
        for ((gx, gy) in corners) {
            val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val color = boardPixels[py * stride + px]
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            if (!(g > r + 35 && g > b + 20)) {
                bgLumSum += (r * 299 + g * 587 + b * 114) / 1000
                bgCount++
            }
        }
        val bgLum = if (bgCount > 0) bgLumSum / bgCount else if ((row + col) % 2 == 0) 54 else 34

        val grid = Array(N) { BooleanArray(N) }
        var whitePixels = 0
        var blackPixels = 0

        for (gy in 2 until N - 2) {
            for (gx in 2 until N - 2) {
                if (gx <= 8 && gy <= 6) continue // пропускаем метку оверлея
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val color = boardPixels[py * stride + px]
                val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)

                if (g > 180 && g > r + 85 && r < 90) continue // линия оверлея

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum >= 160 && r >= 120) {
                    grid[gy][gx] = true
                    whitePixels++
                } else if (lum >= max(68, bgLum + 18) && b >= r + 2) {
                    grid[gy][gx] = true
                    blackPixels++
                }
            }
        }

        val total = whitePixels + blackPixels
        if (total < 32) return null

        val isWhite = whitePixels >= blackPixels

        // Даунскейлим контур клетки в матрицу 12x12 для прямого сопоставления
        val sample12 = Array(12) { BooleanArray(12) }
        for (y12 in 0 until 12) {
            for (x12 in 0 until 12) {
                val sy = y12 * 2
                val sx = x12 * 2
                var votes = 0
                if (grid[sy][sx]) votes++
                if (grid[sy][sx + 1]) votes++
                if (grid[sy + 1][sx]) votes++
                if (grid[sy + 1][sx + 1]) votes++
                sample12[y12][x12] = votes >= 2
            }
        }

        fun matchScore(tpl: Array<String>): Int {
            var score = 0
            for (y in 0 until 12) {
                for (x in 0 until 12) {
                    val exp = tpl[y][x] == '1'
                    val actual = sample12[y][x]
                    if (exp == actual) score += 2
                    else score -= 1
                }
            }
            return score
        }

        val pScore = matchScore(TPL_PAWN) + if (row in 1..6) 20 else -10
        val nScore = matchScore(TPL_KNIGHT)
        val bScore = matchScore(TPL_BISHOP)
        val rScore = matchScore(TPL_ROOK)
        val qScore = matchScore(TPL_QUEEN)
        val kScore = matchScore(TPL_KING)

        val scores = listOf(
            'p' to pScore,
            'n' to nScore,
            'b' to bScore,
            'r' to rScore,
            'q' to qScore,
            'k' to kScore
        )
        val best = scores.maxByOrNull { it.second } ?: ('p' to 0)

        return Triple(isWhite, best.first, kScore)
    }

    private fun detectContinueButton(bmp: Bitmap): Boolean {
        val yStart = (bmp.height * 0.85f).toInt()
        val yEnd = (bmp.height * 0.96f).toInt()
        val xStart = (bmp.width * 0.15f).toInt()
        val xEnd = (bmp.width * 0.85f).toInt()
        var green = 0; var total = 0
        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (g > 170 && g > r + 55 && g > b + 80) green++
                total++; x += 16
            }
            y += 10
        }
        return total > 0 && (green.toFloat() / total) > 0.35f
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
