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

    private val LIGHT_CELL_RGB = intArrayOf(0x20, 0x2F, 0x36)
    private val DARK_CELL_RGB  = intArrayOf(0x13, 0x1F, 0x24)

    fun isFrameStable(prev: Bitmap?, curr: Bitmap, boardTopPx: Int, boardSizePx: Int): Boolean {
        if (prev == null || prev.width != curr.width || prev.height != curr.height) return false
        var diffSum = 0L
        var samples = 0
        val step = max(8, boardSizePx / 32)
        val bottom = min(curr.height - 1, boardTopPx + boardSizePx)

        for (y in boardTopPx until bottom step step) {
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
        return avgDiff < 0.015f
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
                grid[r][c] = classifyCell(bitmap, cellX, cellY, cw, ch, (r + c) % 2 == 0)
            }
        }

        val canonicalGrid = Array(8) { CharArray(8) { '.' } }
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (whiteAtBottom) canonicalGrid[r][c] = grid[r][c]
                else canonicalGrid[r][c] = grid[7 - r][7 - c]
            }
        }

        normalizeKings(canonicalGrid)

        val sideToMove = if (whiteAtBottom) 'w' else 'b'
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

    private fun classifyCell(
        bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int, isLightSquare: Boolean
    ): Char {
        if (x0 < 0 || y0 < 0 || x0 + w > bmp.width || y0 + h > bmp.height) return '.'

        val bgRef = if (isLightSquare) LIGHT_CELL_RGB else DARK_CELL_RGB

        var brightPixels = 0
        var midSlatePixels = 0
        var nonBgPixels = 0
        var totalSamples = 0

        var minY = h; var maxY = 0; var minX = w; var maxX = 0

        var topThirdPixels = 0
        var midThirdPixels = 0
        var botThirdPixels = 0
        var leftHalfTop = 0
        var rightHalfTop = 0
        var topCenterColumn = 0

        val startX = (w * 0.14f).toInt()
        val endX = (w * 0.86f).toInt()
        val startY = (h * 0.08f).toInt()
        val endY = (h * 0.90f).toInt()

        for (dy in startY until endY step 2) {
            for (dx in startX until endX step 2) {
                val px = bmp.getPixel(x0 + dx, y0 + dy)
                val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                totalSamples++

                val diffBg = abs(r - bgRef[0]) + abs(g - bgRef[1]) + abs(b - bgRef[2])
                val isPiecePixel = diffBg > 36 && !(g > r + 35 && lum in 90..165)

                if (isPiecePixel) {
                    nonBgPixels++
                    if (lum >= 165) brightPixels++
                    else if (lum in 48..150) midSlatePixels++

                    if (dy < minY) minY = dy
                    if (dy > maxY) maxY = dy
                    if (dx < minX) minX = dx
                    if (dx > maxX) maxX = dx

                    val relY = (dy - startY).toFloat() / (endY - startY)
                    if (relY < 0.36f) {
                        topThirdPixels++
                        if (dx < w / 2) leftHalfTop++ else rightHalfTop++
                        if (dx in (w * 0.42f).toInt()..(w * 0.58f).toInt()) topCenterColumn++
                    } else if (relY < 0.68f) {
                        midThirdPixels++
                    } else {
                        botThirdPixels++
                    }
                }
            }
        }

        if (totalSamples == 0) return '.'
        val fillRatio = nonBgPixels.toFloat() / totalSamples
        if (fillRatio < 0.13f) return '.'

        val isWhite = brightPixels > nonBgPixels * 0.28f

        val pieceHeightRatio = (maxY - minY).toFloat() / h
        val pieceWidthRatio = (maxX - minX).toFloat() / w
        val topToMidRatio = if (midThirdPixels > 0) topThirdPixels.toFloat() / midThirdPixels else 0f
        val asymmetry = if (topThirdPixels > 0) abs(leftHalfTop - rightHalfTop).toFloat() / topThirdPixels else 0f

        val pieceChar = when {
            pieceHeightRatio < 0.58f && fillRatio < 0.42f -> 'p'
            asymmetry > 0.24f && topToMidRatio > 0.45f -> 'n'
            topToMidRatio > 0.78f && pieceHeightRatio < 0.72f && asymmetry <= 0.20f -> 'r'
            topToMidRatio > 0.72f && pieceWidthRatio > 0.60f && fillRatio > 0.40f -> 'q'
            pieceHeightRatio >= 0.73f && topCenterColumn > topThirdPixels * 0.26f -> 'k'
            else -> 'b'
        }

        return if (isWhite) pieceChar.uppercaseChar() else pieceChar
    }

    private fun normalizeKings(grid: Array<CharArray>) {
        var wkCount = 0; var bkCount = 0
        for (r in 0..7) {
            for (c in 0..7) {
                if (grid[r][c] == 'K') wkCount++
                if (grid[r][c] == 'k') bkCount++
                if ((r == 0 || r == 7) && (grid[r][c] == 'P' || grid[r][c] == 'p')) {
                    grid[r][c] = if (grid[r][c] == 'P') 'B' else 'b'
                }
            }
        }
        if (wkCount == 0 && grid[7][4] in charArrayOf('Q', 'B', 'R')) grid[7][4] = 'K'
        if (bkCount == 0 && grid[0][4] in charArrayOf('q', 'b', 'r')) grid[0][4] = 'k'
    }

    private fun buildMidGameFen(grid: Array<CharArray>, activeColor: Char): String {
        val sb = StringBuilder()
        for (r in 0..7) {
            var empty = 0
            for (c in 0..7) {
                val ch = grid[r][c]
                if (ch == '.') empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r < 7) sb.append('/')
        }

        val castling = StringBuilder()
        if (grid[7][4] == 'K') {
            if (grid[7][7] == 'R') castling.append('K')
            if (grid[7][0] == 'R') castling.append('Q')
        }
        if (grid[0][4] == 'k') {
            if (grid[0][7] == 'r') castling.append('k')
            if (grid[0][0] == 'r') castling.append('q')
        }
        val castlingStr = if (castling.isEmpty()) "-" else castling.toString()
        return "$sb $activeColor $castlingStr - 0 1"
    }

    private fun validatePosition(grid: Array<CharArray>): Pair<Boolean, String> {
        var wk = 0; var bk = 0; var totalPieces = 0
        for (r in 0..7) for (c in 0..7) {
            when (grid[r][c]) {
                'K' -> { wk++; totalPieces++ }
                'k' -> { bk++; totalPieces++ }
                '.' -> {}
                else -> totalPieces++
            }
        }
        if (wk != 1 || bk != 1) return false to "Ожидание доски (K:$wk k:$bk)"
        if (totalPieces < 2 || totalPieces > 32) return false to "Калибровка ($totalPieces фиг.)"
        return true to "Позиция ОК ($totalPieces фиг.)"
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
