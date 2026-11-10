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
        whiteAtBottom: Boolean
    ): ScanResult {
        val grid = Array(8) { CharArray(8) { '.' } }
        val cellSize = boardSizePx / 8f

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val cellX = (boardLeftPx + c * cellSize).toInt()
                val cellY = (boardTopPx + r * cellSize).toInt()
                val cw = cellSize.toInt()
                val ch = cellSize.toInt()
                grid[r][c] = classifyDuolingoPiece(bitmap, cellX, cellY, cw, ch)
            }
        }

        // Если whiteAtBottom == false (играем за черных), то визуальный верх экрана (r=0) 
        // соответствует 1-й горизонтали белых (рядам соперника), а нижний ряд (r=7) — 8-й горизонтали черных.
        val canonicalGrid = Array(8) { CharArray(8) { '.' } }
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (whiteAtBottom) {
                    canonicalGrid[r][c] = grid[r][c]
                } else {
                    // Разворачиваем доску по вертикали и горизонтали для правильного FEN черных
                    canonicalGrid[r][c] = invertPieceColor(grid[7 - r][7 - c])
                }
            }
        }

        normalizeBoardState(canonicalGrid, whiteAtBottom)

        // Возвращаем метки в экранные координаты для отрисовки оверлея
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (whiteAtBottom) {
                    grid[r][c] = canonicalGrid[r][c]
                } else {
                    grid[r][c] = invertPieceColor(canonicalGrid[7 - r][7 - c])
                }
            }
        }

        val sideToMove = 'b' // При игре за черных бот рассчитывает ход черных
        val fen = buildMidGameFen(canonicalGrid, sideToMove)
        val (valid, note) = validatePosition(canonicalGrid)
        val continueActive = detectContinueButton(bitmap)

        return ScanResult(
            board = grid,
            fen = fen,
            isValid = valid,
            continueButtonActive = continueActive,
            statusNote = note
        )
    }

    private fun invertPieceColor(p: Char): Char {
        if (p == '.') return '.'
        return if (p.isUpperCase()) p.lowercaseChar() else p.uppercaseChar()
    }

    private fun classifyDuolingoPiece(bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int): Char {
        if (x0 < 0 || y0 < 0 || x0 + w > bmp.width || y0 + h > bmp.height || w < 24 || h < 24) return '.'

        var bgR = 0; var bgG = 0; var bgB = 0; var bgCount = 0
        for (dy in (h * 0.14f).toInt() until (h * 0.48f).toInt() step 3) {
            for (dx in listOf((w * 0.05f).toInt(), (w * 0.94f).toInt())) {
                val px = bmp.getPixel(x0 + dx, y0 + dy)
                bgR += Color.red(px); bgG += Color.green(px); bgB += Color.blue(px)
                bgCount++
            }
        }
        if (bgCount > 0) { bgR /= bgCount; bgG /= bgCount; bgB /= bgCount }
        val bgLum = (bgR * 299 + bgG * 587 + bgB * 114) / 1000

        val xStart = (w * 0.12f).toInt()
        val xEnd = (w * 0.88f).toInt()
        val yStart = (h * 0.04f).toInt()
        val yEnd = (h * 0.76f).toInt()
        val scanW = xEnd - xStart
        val scanH = yEnd - yStart
        if (scanW <= 8 || scanH <= 8) return '.'

        val mask = Array(scanH) { BooleanArray(scanW) }
        var fgPixels = 0
        var whiteVote = 0

        for (dy in 0 until scanH) {
            for (dx in 0 until scanW) {
                val px = bmp.getPixel(x0 + xStart + dx, y0 + yStart + dy)
                val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                val diffBg = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
                if (diffBg >= 36 && lum > bgLum + 15) {
                    mask[dy][dx] = true
                    fgPixels++
                    if (lum >= 130) whiteVote++
                }
            }
        }

        val fillRatio = fgPixels.toFloat() / (scanW * scanH)
        if (fillRatio < 0.11f) return '.'

        val isWhite = whiteVote > fgPixels * 0.40f

        val wProfile = FloatArray(scanH)
        var topY = -1

        for (dy in 0 until scanH) {
            var rMin = scanW; var rMax = -1; var cnt = 0
            for (dx in 0 until scanW) {
                if (mask[dy][dx]) {
                    if (dx < rMin) rMin = dx
                    if (dx > rMax) rMax = dx
                    cnt++
                }
            }
            if (cnt >= 2 && rMax > rMin) {
                val rw = (rMax - rMin + 1).toFloat() / scanW
                wProfile[dy] = rw
                if (topY == -1 && rw >= 0.14f) topY = dy
            }
        }

        if (topY == -1) return '.'
        val topStartFrac = topY.toFloat() / scanH

        var maxHeadW = 0f; var sumHeadW = 0f; var headRows = 0
        for (dy in (scanH * 0.12f).toInt() until (scanH * 0.52f).toInt()) {
            if (wProfile[dy] > 0.05f) {
                if (wProfile[dy] > maxHeadW) maxHeadW = wProfile[dy]
                sumHeadW += wProfile[dy]; headRows++
            }
        }
        val avgHeadW = if (headRows > 0) sumHeadW / headRows else 0f

        if (maxHeadW < 0.54f || avgHeadW < 0.43f || topStartFrac >= 0.19f) {
            return if (isWhite) 'P' else 'p'
        }

        val pieceType = if (maxHeadW >= 0.71f) 'q' else 'k'
        return if (isWhite) pieceType.uppercaseChar() else pieceType
    }

    private fun normalizeBoardState(grid: Array<CharArray>, whiteAtBottom: Boolean) {
        enforceSingleKing(grid, isWhite = true)
        enforceSingleKing(grid, isWhite = false)
    }

    private fun enforceSingleKing(grid: Array<CharArray>, isWhite: Boolean) {
        val kingChar = if (isWhite) 'K' else 'k'
        val queens = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) for (c in 0..7) if (grid[r][c] == kingChar) queens.add(r to c)
        if (queens.isEmpty()) {
            loop@ for (r in 0..7) {
                for (c in 0..7) {
                    val ch = grid[r][c]
                    if (ch != '.' && ch.isUpperCase() == isWhite) {
                        grid[r][c] = kingChar
                        break@loop
                    }
                }
            }
        }
    }

    private fun buildMidGameFen(grid: Array<CharArray>, activeColor: Char): String {
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
        for (r in 0..7) {
            for (c in 0..7) {
                when (grid[r][c]) {
                    'K' -> { wk++; total++ }
                    'k' -> { bk++; total++ }
                    '.' -> {}
                    else -> total++
                }
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
