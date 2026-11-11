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
    val statusNote: String
)

object BoardRecognizer {

    // Ручные правки пользователя для конкретных клеток (r, c)
    val manualOverrides = mutableMapOf<Pair<Int, Int>, Char>()

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
        boardSizePx: Int,
        whiteAtBottom: Boolean,
        lockedFenState: String?
    ): ScanResult {
        val grid = Array(8) { CharArray(8) { '.' } }
        val cellSize = boardSizePx / 8f

        // Если есть заблокированное состояние (память после включения авто), используем его как базу
        if (!lockedFenState.isNullOrEmpty()) {
            val fenBoard = lockedFenState.split(" ")[0]
            val rows = fenBoard.split("/")
            for (r in 0..7) {
                var c = 0
                for (ch in rows[r]) {
                    if (ch.isDigit()) {
                        c += ch.digitToInt()
                    } else if (c < 8) {
                        grid[r][c] = if (whiteAtBottom) ch else invertPiece(ch)
                        c++
                    }
                }
            }
        } else {
            // Стандартное сканирование
            for (r in 0 until 8) {
                for (c in 0 until 8) {
                    val cellX = (boardLeftPx + c * cellSize).toInt()
                    val cellY = (boardTopPx + r * cellSize).toInt()
                    val cw = cellSize.toInt()
                    val ch = cellSize.toInt()
                    grid[r][c] = classifySimple(bitmap, cellX, cellY, cw, ch, whiteAtBottom, r)
                }
            }
        }

        // Применяем ручные правки пользователя, если они заданы
        for ((pos, ch) in manualOverrides) {
            if (pos.first in 0..7 && pos.second in 0..7) {
                grid[pos.first][pos.second] = if (whiteAtBottom) ch else invertPiece(ch)
            }
        }

        val canonicalGrid = Array(8) { CharArray(8) { '.' } }
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                canonicalGrid[r][c] = if (whiteAtBottom) grid[r][c] else invertPiece(grid[7 - r][7 - c])
            }
        }

        enforceKings(canonicalGrid)

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                grid[r][c] = if (whiteAtBottom) canonicalGrid[r][c] else invertPiece(canonicalGrid[7 - r][7 - c])
            }
        }

        val sideToMove = if (whiteAtBottom) 'w' else 'b'
        val fen = buildFen(canonicalGrid, sideToMove)
        val continueActive = detectContinueButton(bitmap)

        return ScanResult(
            board = grid,
            fen = fen,
            isValid = true,
            continueButtonActive = continueActive,
            statusNote = "Позиция активна (Правки: ${manualOverrides.size})"
        )
    }

    private fun classifySimple(
        bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int, whiteAtBottom: Boolean, screenRow: Int
    ): Char {
        if (x0 < 0 || y0 < 0 || x0 + w > bmp.width || y0 + h > bmp.height || w < 24 || h < 24) return '.'
        var fg = 0; var whiteVotes = 0
        val xStart = (w * 0.15f).toInt(); val xEnd = (w * 0.85f).toInt()
        val yStart = (h * 0.10f).toInt(); val yEnd = (h * 0.80f).toInt()

        for (dy in yStart until yEnd step 3) {
            for (dx in xStart until xEnd step 3) {
                val px = bmp.getPixel(x0 + dx, y0 + dy)
                val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum > 50) {
                    fg++
                    if (lum >= 130) whiteVotes++
                }
            }
        }
        if (fg < (xEnd - xStart) * (yEnd - yStart) * 0.06f) return '.'

        val isWhite = whiteVotes > fg * 0.40f
        val baseChar = if (screenRow in 2..5) 'p' else 'q'
        return if (isWhite) baseChar.uppercaseChar() else baseChar
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

    private fun detectContinueButton(bmp: Bitmap): Boolean {
        val x = (bmp.width * 0.60f).toInt()
        val y = (bmp.height * 0.91f).toInt()
        if (x !in 0 until bmp.width || y !in 0 until bmp.height) return false
        val px = bmp.getPixel(x, y)
        val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
        return g > 160 && g > r + 50 && g > b + 70
    }
}
