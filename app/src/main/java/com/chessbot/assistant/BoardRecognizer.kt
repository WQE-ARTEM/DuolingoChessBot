package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    // Возвращает матрицу 8x8 в экранных координатах: '.' = пусто, 'W' = белая фигура, 'B' = чёрная фигура
    fun scanPresence(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int
    ): Array<CharArray>? {
        val left = boardLeftPx.coerceIn(0, max(0, bitmap.width - 64))
        val top = boardTopPx.coerceIn(0, max(0, bitmap.height - 64))
        val size = min(boardSizePx, min(bitmap.width - left, bitmap.height - top))
        if (size < 120) return null

        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, left, top, size, size)
        val cellSize = size / 8f

        val presence = Array(8) { CharArray(8) { '.' } }
        for (r in 0..7) {
            for (c in 0..7) {
                presence[r][c] = detectCellColor(pixels, size, c * cellSize, r * cellSize, cellSize, r, c)
            }
        }
        return presence
    }

    private fun detectCellColor(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        row: Int,
        col: Int
    ): Char {
        val N = 20
        var bgLumSum = 0; var bgCnt = 0
        val corners = arrayOf(2 to 18, 17 to 18, 17 to 2, 2 to 15)
        for ((gx, gy) in corners) {
            val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val c = pixels[py * stride + px]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            bgLumSum += (r * 299 + g * 587 + b * 114) / 1000
            bgCnt++
        }
        val bgLum = if (bgCnt > 0) bgLumSum / bgCnt else if ((row + col) % 2 == 0) 54 else 34

        var fg = 0; var whiteVotes = 0; var baseFg = 0
        for (gy in 4 until N - 2) {
            for (gx in 4 until N - 4) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                // Игнорируем ярко-зелёную стрелку и жёлтую рамку выделения
                if (g > 185 && g > r + 80) continue
                if (r > 200 && g > 180 && b < 60) continue

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum >= 165 && r >= 120) {
                    fg++
                    whiteVotes++
                    if (gy >= 12) baseFg++
                } else if (lum >= max(72, bgLum + 20) && b >= r + 2) {
                    fg++
                    if (gy >= 12) baseFg++
                }
            }
        }

        if (fg < 16 || baseFg < 5) return '.'
        return if (whiteVotes >= fg * 0.38f) 'W' else 'B'
    }
}
