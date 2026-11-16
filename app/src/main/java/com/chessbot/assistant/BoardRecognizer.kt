package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object BoardRecognizer {

    private var reusablePixels = IntArray(0)

    private class CellMetrics {
        var occupied = false
        var isWhite = false
        var relHeight = 0f
        var topWidthRatio = 0f
        var neckWidthRatio = 0f
        var topPeaks = 0
        var centerTip = 0f
        var asymmetry = 0f

        fun reset() {
            occupied = false
            isWhite = false
            relHeight = 0f
            topWidthRatio = 0f
            neckWidthRatio = 0f
            topPeaks = 0
            centerTip = 0f
            asymmetry = 0f
        }
    }

    private val metricsGrid = Array(8) { Array(8) { CellMetrics() } }
    private val maskBuffer = Array(32) { BooleanArray(32) }
    private val widthsBuffer = IntArray(32)
    private val rSamples = IntArray(8)
    private val gSamples = IntArray(8)
    private val bSamples = IntArray(8)

    fun findBoardTopY(bitmap: Bitmap, expectedTopY: Int, isChessCom: Boolean): Int {
        val w = bitmap.width
        val h = bitmap.height
        val cellW = w / 8
        val minY = (h * 0.18f).toInt().coerceAtLeast(10)
        val maxY = (h * 0.50f).toInt().coerceAtMost(h - w - 10)

        var bestY = expectedTopY
        var bestScore = -1

        for (y in minY..maxY step 3) {
            var score = 0
            for (rowCheck in intArrayOf(0, 3, 7)) {
                val ry = y + rowCheck * cellW + (cellW * 0.14f).toInt()
                if (ry >= h - 8) continue
                var prevLum = -1
                for (c in 0..7) {
                    val rx = c * cellW + (cellW * 0.15f).toInt()
                    val p = bitmap.getPixel(rx.coerceIn(0, w - 1), ry.coerceIn(0, h - 1))
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    val lum = (r * 299 + g * 587 + b * 114) / 1000

                    if (prevLum != -1) {
                        val diff = abs(lum - prevLum)
                        if (isChessCom) {
                            if (diff in 24..165 && (g >= r - 15)) score += 2 else score -= 3
                        } else {
                            if (diff in 8..52 && lum in 16..95 && b >= r - 10) score += 2 else score -= 3
                        }
                    }
                    prevLum = lum
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestY = y
            }
        }

        if (bestScore < 12) return expectedTopY

        var exactY = bestY
        while (exactY > minY) {
            val p0 = bitmap.getPixel((cellW * 0.15f).toInt(), exactY - 1)
            val p1 = bitmap.getPixel(cellW + (cellW * 0.15f).toInt(), exactY - 1)
            val l0 = (Color.red(p0) * 299 + Color.green(p0) * 587 + Color.blue(p0) * 114) / 1000
            val l1 = (Color.red(p1) * 299 + Color.green(p1) * 587 + Color.blue(p1) * 114) / 1000
            val diff = abs(l0 - l1)
            if ((isChessCom && diff >= 24) || (!isChessCom && diff in 8..52 && l0 in 16..95)) {
                exactY--
            } else {
                break
            }
        }
        return exactY
    }

    @Synchronized
    fun scanPresence(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int,
        isChessCom: Boolean
    ): Array<CharArray>? {
        val full = scanFullBoardFromScreen(bitmap, boardLeftPx, boardTopPx, boardSizePx, isChessCom) ?: return null
        val presence = Array(8) { CharArray(8) { '.' } }
        for (r in 0..7) {
            for (c in 0..7) {
                val p = full[r][c]
                presence[r][c] = when {
                    p == '.' -> '.'
                    p.isUpperCase() -> 'W'
                    else -> 'B'
                }
            }
        }
        return presence
    }

    @Synchronized
    fun scanFullBoardFromScreen(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int,
        isChessCom: Boolean
    ): Array<CharArray>? {
        val left = boardLeftPx.coerceIn(0, max(0, bitmap.width - 64))
        val top = boardTopPx.coerceIn(0, max(0, bitmap.height - 64))
        val size = min(boardSizePx, min(bitmap.width - left, bitmap.height - top))
        if (size < 120) return null

        val needed = size * size
        if (reusablePixels.size < needed) {
            reusablePixels = IntArray(needed)
        }
        bitmap.getPixels(reusablePixels, 0, size, left, top, size, size)
        val cellSize = size / 8f

        var topOcc = 0
        var botOcc = 0
        var midOcc = 0

        for (r in 0..7) {
            for (c in 0..7) {
                val m = metricsGrid[r][c]
                analyzeCell(reusablePixels, size, c * cellSize, r * cellSize, cellSize, isChessCom, m)
                if (m.occupied) {
                    when (r) {
                        0, 1 -> topOcc++
                        6, 7 -> botOcc++
                        else -> midOcc++
                    }
                }
            }
        }

        val result = Array(8) { CharArray(8) { '.' } }

        // 100% точное определение стартовой расстановки в начале партии
        if (topOcc == 16 && botOcc == 16 && midOcc == 0) {
            var topWhite = 0
            for (c in 0..7) {
                if (metricsGrid[0][c].isWhite) topWhite++
                if (metricsGrid[1][c].isWhite) topWhite++
            }
            val topIsBlack = topWhite < 8
            val backRow = charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')
            for (c in 0..7) {
                result[0][c] = if (topIsBlack) backRow[c] else backRow[c].uppercaseChar()
                result[1][c] = if (topIsBlack) 'p' else 'P'
                for (r in 2..5) result[r][c] = '.'
                result[6][c] = if (topIsBlack) 'P' else 'p'
                result[7][c] = if (topIsBlack) backRow[c].uppercaseChar() else backRow[c]
            }
            return result
        }

        for (r in 0..7) {
            for (c in 0..7) {
                val m = metricsGrid[r][c]
                if (!m.occupied) {
                    result[r][c] = '.'
                } else {
                    val ch = classifyPiece(m, r)
                    result[r][c] = if (m.isWhite) ch.uppercaseChar() else ch
                }
            }
        }

        validateChessBoard(result)
        return result
    }

    private val bgSamplePoints = arrayOf(
        2 to 10, 2 to 16, 2 to 22,
        29 to 16, 29 to 22,
        6 to 29, 15 to 29, 24 to 29
    )

    private fun analyzeCell(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        isChessCom: Boolean,
        out: CellMetrics
    ) {
        out.reset()
        val N = 32

        for (i in 0 until 8) {
            val (gx, gy) = bgSamplePoints[i]
            val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val c = pixels[py * stride + px]
            rSamples[i] = Color.red(c)
            gSamples[i] = Color.green(c)
            bSamples[i] = Color.blue(c)
        }
        rSamples.sort(); gSamples.sort(); bSamples.sort()
        val bgR = rSamples[4]; val bgG = gSamples[4]; val bgB = bSamples[4]
        val bgLum = (bgR * 299 + bgG * 587 + bgB * 114) / 1000

        var fgCount = 0
        var whitePixels = 0
        var darkPixels = 0
        var minGy = N; var maxGy = -1
        var minGx = N; var maxGx = -1

        for (gy in 0 until N) {
            val rowMask = maskBuffer[gy]
            for (gx in 0 until N) rowMask[gx] = false
        }

        for (gy in 3..28) {
            for (gx in 4..27) {
                if (gx >= 20 && gy <= 9) continue // не трогаем мини-значок в правом верхнем углу

                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                // Пропускаем цвет нашей зелёной/оранжевой стрелки и рамки
                if (g > 205 && g > r + 85 && b < 120) continue
                if (b > 205 && g > 190 && r < 45) continue

                val dr = r - bgR; val dg = g - bgG; val db = b - bgB
                val distSq = dr * dr + dg * dg + db * db
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                val sat = max(r, max(g, b)) - min(r, min(g, b))

                var isFg = false
                if (isChessCom) {
                    val isWhiteBody = r >= 232 && g >= 232 && b >= 224 && sat <= 22
                    val isDarkBody = lum <= 112 && sat <= 30 && (g - r) <= 16
                    if ((isWhiteBody && distSq >= 480) || (isDarkBody && distSq >= 1600) || distSq >= 4600) {
                        isFg = true
                    }
                } else {
                    if (distSq >= 950 && lum >= bgLum + 16) {
                        isFg = true
                    }
                }

                if (isFg) {
                    maskBuffer[gy][gx] = true
                    fgCount++
                    if (isChessCom) {
                        if (lum >= 210 && sat <= 24) whitePixels++
                        else if (lum in 45..112) darkPixels++
                    } else {
                        if (lum >= 188) whitePixels++ else darkPixels++
                    }
                    if (gy < minGy) minGy = gy
                    if (gy > maxGy) maxGy = gy
                    if (gx < minGx) minGx = gx
                    if (gx > maxGx) maxGx = gx
                }
            }
        }

        var baseCount = 0
        for (gy in 20..28) {
            for (gx in 6..25) if (maskBuffer[gy][gx]) baseCount++
        }

        val boxH = maxGy - minGy + 1
        val boxW = maxGx - minGx + 1
        if (fgCount < 44 || baseCount < 14 || boxH < 9 || boxW < 7) return

        out.occupied = true
        out.isWhite = if (isChessCom) {
            whitePixels >= 18 && whitePixels >= darkPixels * 0.40f
        } else {
            whitePixels >= 15 && whitePixels >= fgCount * 0.24f
        }

        var asymSum = 0f
        val centerX = (minGx + maxGx) * 0.5f

        for (i in 0 until boxH) {
            val gy = minGy + i
            var l = N; var r = -1
            for (gx in minGx..maxGx) {
                if (maskBuffer[gy][gx]) {
                    if (gx < l) l = gx
                    if (gx > r) r = gx
                }
            }
            if (r >= l) {
                widthsBuffer[i] = r - l + 1
                asymSum += abs((l + r) * 0.5f - centerX)
            } else {
                widthsBuffer[i] = 0
            }
        }

        val baseStart = (boxH * 0.70f).toInt().coerceIn(0, boxH - 1)
        var baseW = 1
        for (i in baseStart until boxH) if (widthsBuffer[i] > baseW) baseW = widthsBuffer[i]

        val headStart = (boxH * 0.12f).toInt().coerceIn(0, boxH - 1)
        val headEnd = (boxH * 0.32f).toInt().coerceIn(headStart, boxH - 1)
        var headW = 0
        for (i in headStart..headEnd) if (widthsBuffer[i] > headW) headW = widthsBuffer[i]

        val neckStart = (boxH * 0.36f).toInt().coerceIn(0, boxH - 1)
        val neckEnd = (boxH * 0.55f).toInt().coerceIn(neckStart, boxH - 1)
        var neckW = baseW
        for (i in neckStart..neckEnd) {
            val w = widthsBuffer[i]
            if (w in 2 until neckW) neckW = w
        }

        var peaks = 0
        val peakEnd = (boxH * 0.28f).toInt().coerceIn(1, boxH - 1)
        for (i in 0..peakEnd) {
            val gy = minGy + i
            var tr = 0; var prev = false
            for (gx in minGx..maxGx) {
                val cur = maskBuffer[gy][gx]
                if (cur && !prev) tr++
                prev = cur
            }
            if (tr >= 2) peaks += tr
        }

        val tipEnd = (boxH * 0.15f).toInt().coerceIn(0, boxH - 1)
        var tipRows = 0
        for (i in 0..tipEnd) {
            if (widthsBuffer[i] in 1..(baseW / 2 + 1)) tipRows++
        }

        out.relHeight = boxH / 25f
        out.topWidthRatio = headW.toFloat() / baseW.coerceAtLeast(1)
        out.neckWidthRatio = neckW.toFloat() / baseW.coerceAtLeast(1)
        out.topPeaks = peaks
        out.centerTip = tipRows.toFloat()
        out.asymmetry = asymSum / boxH.coerceAtLeast(1)
    }

    private fun classifyPiece(m: CellMetrics, screenRow: Int): Char {
        val backRank = (screenRow == 0 || screenRow == 7)

        if (!backRank && m.relHeight < 0.73f && m.topWidthRatio < 0.72f && m.neckWidthRatio < 0.62f) {
            return 'p'
        }
        if (m.relHeight in 0.68f..0.88f && m.topWidthRatio >= 0.70f && m.neckWidthRatio >= 0.60f && m.centerTip <= 1.5f) {
            return 'r'
        }
        if (m.relHeight >= 0.86f && m.centerTip >= 1.5f && m.topWidthRatio >= 0.68f) {
            return 'k'
        }
        if (m.relHeight >= 0.80f && (m.topPeaks >= 4 || m.topWidthRatio >= 0.78f)) {
            return 'q'
        }
        if (m.asymmetry >= 0.82f || (m.neckWidthRatio >= 0.62f && m.topWidthRatio < 0.74f)) {
            return 'n'
        }
        if (m.relHeight >= 0.75f && m.topWidthRatio < 0.70f) {
            return 'b'
        }
        if (!backRank && m.relHeight < 0.77f) return 'p'
        return if (m.topWidthRatio >= 0.72f) 'r' else 'n'
    }

    private fun validateChessBoard(grid: Array<CharArray>) {
        for (r in intArrayOf(0, 7)) {
            for (c in 0..7) {
                val p = grid[r][c]
                if (p == 'P' || p == 'p') {
                    val m = metricsGrid[r][c]
                    val sub = if (m.topWidthRatio >= 0.70f) 'r' else if (m.asymmetry >= 0.8f) 'n' else 'b'
                    grid[r][c] = if (p.isUpperCase()) sub.uppercaseChar() else sub
                }
            }
        }

        for (whiteSide in booleanArrayOf(true, false)) {
            val kChar = if (whiteSide) 'K' else 'k'
            var bestR = -1; var bestC = -1; var bestRank = -999f
            var kingCount = 0

            for (r in 0..7) {
                for (c in 0..7) {
                    val p = grid[r][c]
                    if (p != '.' && p.isUpperCase() == whiteSide) {
                        val m = metricsGrid[r][c]
                        val rank = m.relHeight * 100f + m.centerTip * 8f - m.topPeaks * 2f
                        if (p == kChar) kingCount++
                        if (rank > bestRank) {
                            bestRank = rank; bestR = r; bestC = c
                        }
                    }
                }
            }

            if (kingCount > 1) {
                var bestKingR = -1; var bestKingC = -1; var maxK = -999f
                for (r in 0..7) {
                    for (c in 0..7) {
                        if (grid[r][c] == kChar) {
                            val m = metricsGrid[r][c]
                            val rk = m.relHeight * 100f + m.centerTip * 8f - m.topPeaks * 2f
                            if (rk > maxK) { maxK = rk; bestKingR = r; bestKingC = c }
                        }
                    }
                }
                for (r in 0..7) {
                    for (c in 0..7) {
                        if (grid[r][c] == kChar && (r != bestKingR || c != bestKingC)) {
                            val m = metricsGrid[r][c]
                            val sub = if (m.topPeaks >= 3 || m.topWidthRatio >= 0.75f) 'q'
                            else if (m.neckWidthRatio >= 0.60f) 'r' else 'b'
                            grid[r][c] = if (whiteSide) sub.uppercaseChar() else sub
                        }
                    }
                }
            } else if (kingCount == 0 && bestR != -1) {
                grid[bestR][bestC] = kChar
            }
        }
    }
}
