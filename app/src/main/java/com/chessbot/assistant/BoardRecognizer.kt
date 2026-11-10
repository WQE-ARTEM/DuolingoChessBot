package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ScanResult(
    val board: Array<CharArray>,
    val fen: String,
    val isValid: Boolean,
    val continueButtonActive: Boolean,
    val detectedWhiteAtBottom: Boolean,
    val statusNote: String
)

object BoardRecognizer {

    fun isFrameStable(prev: Bitmap?, curr: Bitmap, boardTopPx: Int, boardSizePx: Int): Boolean {
        if (prev == null || prev.width != curr.width || prev.height != curr.height) return false
        var diffSum = 0L
        var samples = 0
        val step = max(8, boardSizePx / 32)
        val top = boardTopPx.coerceIn(0, curr.height - 10)
        val bottom = min(curr.height - 1, top + boardSizePx)

        for (y in top until bottom step step) {
            for (x in 0 until min(curr.width, boardSizePx) step step) {
                val p1 = prev.getPixel(x, y)
                val p2 = curr.getPixel(x, y)
                diffSum += abs(Color.red(p1) - Color.red(p2)) +
                           abs(Color.green(p1) - Color.green(p2)) +
                           abs(Color.blue(p1) - Color.blue(p2))
                samples++
            }
        }
        if (samples == 0) return true
        val avgDiff = diffSum.toFloat() / (samples * 3f * 255f)
        return avgDiff < 0.018f
    }

    fun recognizeBoard(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int
    ): ScanResult {
        val grid = Array(8) { CharArray(8) { '.' } }
        val cellSize = boardSizePx / 8f

        // Шаг 1: Автоматически определяем, какие фигуры внизу экрана (строка r=7)
        var bottomWhiteVotes = 0
        var bottomPieceCount = 0

        for (c in 0 until 8) {
            val cellX = (boardLeftPx + c * cellSize).toInt()
            val cellY = (boardTopPx + 7 * cellSize).toInt()
            val (isPiece, isWhite) = analyzeCellColorAndPresence(bitmap, cellX, cellY, cellSize.toInt(), cellSize.toInt())
            if (isPiece) {
                bottomPieceCount++
                if (isWhite) bottomWhiteVotes++
            }
        }

        // Если внизу больше светлых фигур — мы играем за белых, иначе за черных
        val whiteAtBottom = bottomWhiteVotes >= (bottomPieceCount / 2).coerceAtLeast(1)

        // Шаг 2: Сканируем всю доску с учетом определенной стороны
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val cellX = (boardLeftPx + c * cellSize).toInt()
                val cellY = (boardTopPx + r * cellSize).toInt()
                val cw = cellSize.toInt()
                val ch = cellSize.toInt()
                grid[r][c] = classifyPiece(bitmap, cellX, cellY, cw, ch, whiteAtBottom, r)
            }
        }

        val canonicalGrid = Array(8) { CharArray(8) { '.' } }
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (whiteAtBottom) {
                    canonicalGrid[r][c] = grid[r][c]
                } else {
                    canonicalGrid[r][c] = invertPiece(grid[7 - r][7 - c])
                }
            }
        }

        enforceKings(canonicalGrid)

        // Возвращаем в экранную сетку для оверлея
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (whiteAtBottom) {
                    grid[r][c] = canonicalGrid[r][c]
                } else {
                    grid[r][c] = invertPiece(canonicalGrid[7 - r][7 - c])
                }
            }
        }

        // Чей ход: если мы белые — ход белых ('w'), если черные — ход черных ('b')
        val sideToMove = if (whiteAtBottom) 'w' else 'b'
        val fen = buildFen(canonicalGrid, sideToMove)
        val (valid, note) = validatePosition(canonicalGrid)
        val continueActive = detectContinueButton(bitmap)

        return ScanResult(
            board = grid,
            fen = fen,
            isValid = valid,
            continueButtonActive = continueActive,
            detectedWhiteAtBottom = whiteAtBottom,
            statusNote = note
        )
    }

    private fun analyzeCellColorAndPresence(bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int): Pair<Boolean, Boolean> {
        if (x0 < 0 || y0 < 0 || x0 + w > bmp.width || y0 + h > bmp.height || w < 24 || h < 24) return false to false

        var bgR = 0; var bgG = 0; var bgB = 0; var bgCount = 0
        for (dy in (h * 0.15f).toInt() until (h * 0.45f).toInt() step 3) {
            for (dx in listOf((w * 0.08f).toInt(), (w * 0.92f).toInt())) {
                val px = bmp.getPixel(x0 + dx, y0 + dy)
                bgR += Color.red(px); bgG += Color.green(px); bgB += Color.blue(px)
                bgCount++
            }
        }
        if (bgCount > 0) { bgR /= bgCount; bgG /= bgCount; bgB /= bgCount }
        val bgLum = (bgR * 299 + bgG * 587 + bgB * 114) / 1000

        var fg = 0; var whiteVotes = 0
        val xStart = (w * 0.12f).toInt(); val xEnd = (w * 0.88f).toInt()
        val yStart = (h * 0.05f).toInt(); val yEnd = (h * 0.75f).toInt()

        for (dy in yStart until yEnd step 2) {
            for (dx in xStart until xEnd step 2) {
                val px = bmp.getPixel(x0 + dx, y0 + dy)
                val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                val diff = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
                if (diff >= 32 && lum > bgLum + 12) {
                    fg++
                    if (lum >= 120) whiteVotes++
                }
            }
        }

        val present = fg > (xEnd - xStart) * (yEnd - yStart) * 0.08f
        val isWhite = whiteVotes > fg * 0.38f
        return present to isWhite
    }

    private fun classifyPiece(
        bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int, whiteAtBottom: Boolean, screenRow: Int
    ): Char {
        val (hasPiece, isWhite) = analyzeCellColorAndPresence(bmp, x0, y0, w, h)
        if (!hasPiece) return '.'

        val pieceChar = if (screenRow in 2..5) 'p' else 'q'
        return if (isWhite) pieceChar.uppercaseChar() else pieceChar
    }

    private fun invertPiece(p: Char): Char {
        if (p == '.') return '.'
        return if (p.isUpperCase()) p.lowercaseChar() else p.uppercaseChar()
    }

    private fun enforceKings(grid: Array<CharArray>) {
        var wk = false; var bk = false
        for (r in 0..7) for (c in 0..7) {
            if (grid[r][c] == 'K') wk = true
            if (grid[r][c] == 'k') bk = true
        }
        if (!wk) grid[7][4] = 'K'
        if (!bk) grid[0][4] = 'k'
    }

    private fun buildFen(grid: Array<CharArray>, activeColor: Char): String {
        val sb = StringBuilder()
        for (r in 0..7) {
            var empty = 0
            for (c in 0..7) {
                val ch = grid[r][c]
                if (ch == '.') {
                    empty++
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r < 7) sb.append('/')
        }
        return "$sb $activeColor - - 0 1"
    }

    private fun validatePosition(grid: Array<CharArray>): Pair<Boolean, String> {
        var wk = 0; var bk = 0; var total = 0
        for (r in 0..7) for (c in 0..7) {
            when (grid[r][c]) {
                'K' -> { wk++; total++ }
                'k' -> { bk++; total++ }
                '.' -> {}
                else -> total++
            }
        }
        if (wk != 1 || bk != 1) return false to "Ожидание доски (K:$wk k:$bk)"
        return true to "Позиция ОК ($total фиг.)"
    }

    private fun detectContinueButton(bmp: Bitmap): Boolean {
        val x = (bmp.width * 0.60f).toInt()
        val y = (bmp.height * 0.91f).toInt()
        if (x !in 0 until bmp.width || y !in 0 until bmp.height) return false
        val px = bmp.getPixel(x, y)
        val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
        return g > 160 && g > r + 50 && g > b + 70
    }
}
