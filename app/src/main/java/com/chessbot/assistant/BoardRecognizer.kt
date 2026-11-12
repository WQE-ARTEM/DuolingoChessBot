package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    // Автоматически ищет точную координату Y верхнего края доски Duolingo на экране
    fun findBoardTopY(bitmap: Bitmap, expectedTopY: Int): Int {
        val w = bitmap.width
        val h = bitmap.height
        val cellW = w / 8
        val minY = (h * 0.22f).toInt().coerceAtLeast(10)
        val maxY = (h * 0.55f).toInt().coerceAtMost(h - w - 10)

        for (y in minY..maxY step 4) {
            // Проверяем чередование светлых и тёмных клеток Duolingo в первом ряду
            var validAlternations = 0
            var prevLum = -1
            for (c in 0..7) {
                val sampleX = c * cellW + cellW / 5
                val sampleY = y + cellW / 5
                val pixel = bitmap.getPixel(sampleX, sampleY)
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                // Доска Duolingo имеет тёмно-сине-зелёный оттенок (b >= r, g >= r, яркость 22..75)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum !in 18..85 || r > b + 15) {
                    validAlternations = -10
                    break
                }
                if (prevLum != -1 && abs(lum - prevLum) in 8..38) {
                    validAlternations++
                }
                prevLum = lum
            }
            if (validAlternations >= 5) {
                // Уточняем границу с шагом 1 пиксель вверх
                var exactY = y
                while (exactY > minY) {
                    val p0 = bitmap.getPixel(cellW / 5, exactY - 1)
                    val p1 = bitmap.getPixel(cellW + cellW / 5, exactY - 1)
                    val l0 = (Color.red(p0) * 299 + Color.green(p0) * 587 + Color.blue(p0) * 114) / 1000
                    val l1 = (Color.red(p1) * 299 + Color.green(p1) * 587 + Color.blue(p1) * 114) / 1000
                    if (abs(l0 - l1) >= 8 && l0 in 18..85 && l1 in 18..85) {
                        exactY--
                    } else {
                        break
                    }
                }
                return exactY
            }
        }
        return expectedTopY
    }

    // Возвращает матрицу присутствия 'W' (белая), 'B' (чёрная), '.' (пусто) в экранных координатах
    fun scanPresence(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int
    ): Array<CharArray>? {
        val full = scanFullBoardFromScreen(bitmap, boardLeftPx, boardTopPx, boardSizePx) ?: return null
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

    // Полное распознавание доски с экрана (цвет + классификация типа фигуры по геометрии силуэта)
    fun scanFullBoardFromScreen(
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

        val result = Array(8) { CharArray(8) { '.' } }
        for (r in 0..7) {
            for (c in 0..7) {
                result[r][c] = classifyCellPiece(pixels, size, c * cellSize, r * cellSize, cellSize, r, c)
            }
        }
        return result
    }

    private fun classifyCellPiece(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        row: Int,
        col: Int
    ): Char {
        val N = 24
        var bgLumSum = 0; var bgCnt = 0
        val corners = arrayOf(1 to 22, 22 to 22, 22 to 2, 1 to 20)
        for ((gx, gy) in corners) {
            val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val c = pixels[py * stride + px]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            bgLumSum += (r * 299 + g * 587 + b * 114) / 1000
            bgCnt++
        }
        val bgLum = if (bgCnt > 0) bgLumSum / bgCnt else if ((row + col) % 2 == 0) 54 else 34

        var fg = 0
        var whiteVotes = 0
        var topFg = 0      // верхняя часть (корона короля/ферзя/слона)
        var upperMidFg = 0 // голова пешки / зубцы ладьи / голова коня
        var lowerFg = 0    // широкое основание
        var minGy = N
        var maxGx = 0
        var minGx = N
        var topRowPeaks = 0

        for (gy in 3 until N - 2) {
            var rowHasFg = false
            var rowTransitions = 0
            var prevIsFg = false
            for (gx in 4 until N - 4) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                // Пропускаем цвет нашей зелёной стрелки и оверлея
                if (g > 180 && g > r + 70) continue
                if (b > 200 && g > 190 && r < 40) continue

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                var isPiece = false
                if (lum >= 165 && r >= 120) {
                    isPiece = true
                    whiteVotes++
                } else if (lum >= max(70, bgLum + 19) && b >= r) {
                    isPiece = true
                }

                if (isPiece) {
                    fg++
                    rowHasFg = true
                    if (gy < minGy) minGy = gy
                    if (gx < minGx) minGx = gx
                    if (gx > maxGx) maxGx = gx
                    if (gy in 3..7) topFg++
                    else if (gy in 8..13) upperMidFg++
                    else if (gy >= 14) lowerFg++
                    if (!prevIsFg) rowTransitions++
                }
                prevIsFg = isPiece
            }
            if (gy in 5..9 && rowTransitions >= 2) topRowPeaks += rowTransitions
        }

        if (fg < 24 || lowerFg < 8) return '.'
        val isWhite = whiteVotes >= fg * 0.38f
        val width = maxGx - minGx + 1

        // Определяем тип фигуры по высоте (minGy), площади (fg) и форме верхушки
        val pieceChar = when {
            // Пешка: самая низкая фигура (minGy >= 7) и компактная площадь
            minGy >= 7 && fg < 105 -> 'p'
            // Король: очень высокий (крест сверху minGy <= 4) и массивный
            minGy <= 4 && fg >= 125 -> 'k'
            // Ферзь: широкая корона с несколькими зубцами сверху (topRowPeaks >= 4) и большая площадь
            topRowPeaks >= 4 && fg >= 105 -> 'q'
            // Ладья: широкая плоская башня сверху, среднесжатая по высоте
            minGy in 5..7 && width >= 11 && topFg < 18 && upperMidFg >= 34 -> 'r'
            // Слон: узкий высокий купол с прорезью
            minGy <= 5 && width <= 11 -> 'b'
            // Конь: асимметричная голова средней высоты
            minGy in 5..6 && fg in 85..135 -> 'n'
            // Запасная эвристика по размеру
            fg < 95 -> 'p'
            fg > 135 -> 'q'
            else -> 'r'
        }

        return if (isWhite) pieceChar.uppercaseChar() else pieceChar
    }
}
