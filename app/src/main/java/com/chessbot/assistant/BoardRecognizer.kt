package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    private var reusablePixels = IntArray(0)

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
                            if (diff in 24..165) score += 2 else score -= 3
                        } else {
                            if (diff in 8..55 && lum in 15..95) score += 2 else score -= 3
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
        return if (bestScore >= 10) bestY else expectedTopY
    }

    @Synchronized
    fun scanPresence(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int,
        isChessCom: Boolean
    ): Array<CharArray>? {
        val full = scanFullBoardFromScreen(bitmap, boardLeftPx, boardTopPx, boardSizePx, isChessCom, true) ?: return null
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
        isChessCom: Boolean,
        playerIsWhite: Boolean = true
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

        val result = Array(8) { CharArray(8) { '.' } }

        for (sr in 0..7) {
            for (sc in 0..7) {
                val cellX = sc * cellSize
                val cellY = sr * cellSize
                val cellScan = analyzeCellPixels(reusablePixels, size, cellX, cellY, cellSize, isChessCom)

                val isActuallyWhite = if (playerIsWhite) (sr >= 4) else (sr <= 3)

                if (cellScan.occupied) {
                    val ch = cellScan.shapeType
                    result[sr][sc] = if (isActuallyWhite) ch.uppercaseChar() else ch
                } else {
                    result[sr][sc] = '.'
                }
            }
        }

        // Защита от ложных срабатываний анимаций: если в середине партии на пустых клетках вдруг привиделось много ферзей, убираем мусор
        cleanAnimationArtifacts(result)

        return result
    }

    private data class CellResult(
        val occupied: Boolean,
        val shapeType: Char
    )

    private val bgSampleOffsets = arrayOf(2 to 16, 2 to 24, 29 to 16, 16 to 29)

    private fun analyzeCellPixels(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        isChessCom: Boolean
    ): CellResult {
        val N = 32
        var bgR = 0; var bgG = 0; var bgB = 0
        for ((gx, gy) in bgSampleOffsets) {
            val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val c = pixels[py * stride + px]
            bgR += Color.red(c); bgG += Color.green(c); bgB += Color.blue(c)
        }
        bgR /= 4; bgG /= 4; bgB /= 4

        var fgCount = 0
        var minGy = N; var maxGy = -1
        var minGx = N; var maxGx = -1
        val widths = IntArray(N)

        for (gy in 3..28) {
            var rowMin = N; var rowMax = -1
            for (gx in 4..27) {
                if (gx >= 20 && gy <= 9) continue // правый верхний угол под значок
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                if (g > 210 && g > r + 80) continue
                val dr = r - bgR; val dg = g - bgG; val db = b - bgB
                val dist = sqrt((dr * dr + dg * dg + db * db).toDouble())
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                // Повышенный порог отсечки (38.0), чтобы анимационные вспышки съедания не принимались за фигуры
                if (dist >= 38.0 && lum >= 20) {
                    fgCount++
                    if (gy < minGy) minGy = gy
                    if (gy > maxGy) maxGy = gy
                    if (gx < minGx) minGx = gx
                    if (gx > maxGx) maxGx = gx
                    if (gx < rowMin) rowMin = gx
                    if (gx > rowMax) rowMax = gx
                }
            }
            if (rowMax >= rowMin) {
                widths[gy] = rowMax - rowMin + 1
            }
        }

        var baseMass = 0
        for (gy in 21..28) {
            for (gx in 7..24) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                if (abs(Color.red(c) - bgR) >= 38) baseMass++
            }
        }

        val boxH = maxGy - minGy + 1
        // Увеличен минимальный порог плотности (fgCount >= 46), чтобы исключить мерцающие частицы анимации
        if (fgCount < 46 || baseMass < 15 || boxH < 9) {
            return CellResult(false, '.')
        }

        val baseStart = (boxH * 0.70f).toInt().coerceIn(0, boxH - 1)
        var baseW = 1
        for (i in baseStart until boxH) {
            val w = widths[minGy + i]
            if (w > baseW) baseW = w
        }

        val headStart = (boxH * 0.10f).toInt().coerceIn(0, boxH - 1)
        val headEnd = (boxH * 0.30f).toInt().coerceIn(headStart, boxH - 1)
        var headW = 0
        for (i in headStart..headEnd) {
            val w = widths[minGy + i]
            if (w > headW) headW = w
        }

        val relH = boxH / 24f
        val topRatio = headW.toFloat() / baseW.coerceAtLeast(1)

        val shape = when {
            relH < 0.70f && topRatio < 0.68f -> 'p'
            relH >= 0.84f && topRatio >= 0.65f -> 'k'
            relH >= 0.76f && topRatio >= 0.74f -> 'q'
            topRatio >= 0.70f -> 'r'
            relH >= 0.73f && topRatio < 0.65f -> 'b'
            else -> 'n'
        }

        return CellResult(true, shape)
    }

    private fun cleanAnimationArtifacts(grid: Array<CharArray>) {
        // Если на доске каким-то образом появилось больше 2 королевств каждого цвета — убираем лишние фантомы
        for (whiteSide in booleanArrayOf(true, false)) {
            val kChar = if (whiteSide) 'K' else 'k'
            val kings = mutableListOf<Pair<Int, Int>>()
            for (r in 0..7) {
                for (c in 0..7) {
                    if (grid[r][c] == kChar) kings.add(r to c)
                }
            }
            if (kings.size > 1) {
                // Оставляем только нижнего/верхнего истинного короля, остальные превращаем в пешек или пустоту
                for (i in 1 until kings.size) {
                    val (r, c) = kings[i]
                    grid[r][c] = if (whiteSide) 'P' else 'p'
                }
            }
        }
    }

    private fun sqrt(d: Double): Double = kotlin.math.sqrt(d)
}
