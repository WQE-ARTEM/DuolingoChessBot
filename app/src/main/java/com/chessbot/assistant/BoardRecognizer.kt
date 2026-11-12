package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    // Автоматически находит верхний край доски и для Duolingo, и для Chess.com
    fun findBoardTopY(bitmap: Bitmap, expectedTopY: Int, isChessCom: Boolean): Int {
        val w = bitmap.width
        val h = bitmap.height
        val cellW = w / 8
        val minY = (h * 0.20f).toInt().coerceAtLeast(10)
        val maxY = (h * 0.55f).toInt().coerceAtMost(h - w - 10)

        for (y in minY..maxY step 3) {
            var validAlternations = 0
            var prevLum = -1
            for (c in 0..7) {
                // Берем точку в правом верхнем углу каждой клетки (там нет цифр координат и фигур)
                val sampleX = c * cellW + (cellW * 0.82f).toInt()
                val sampleY = y + (cellW * 0.14f).toInt()
                val pixel = bitmap.getPixel(sampleX.coerceIn(0, w - 1), sampleY.coerceIn(0, h - 1))
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                if (isChessCom) {
                    // В Chess.com клетки либо светлые бежевые/жёлтые (lum > 180), либо зелёные (g > r + 15, lum 95..170)
                    val isLightSq = lum > 175 && r > 180 && g > 180
                    val isGreenSq = lum in 90..175 && g > r + 12 && g > b + 15
                    if (!isLightSq && !isGreenSq) {
                        validAlternations = -10
                        break
                    }
                    if (prevLum != -1 && abs(lum - prevLum) >= 35) {
                        validAlternations++
                    }
                } else {
                    // В Duolingo клетки тёмно-сине-зелёные (lum 18..85, b >= r)
                    if (lum !in 18..88 || r > b + 15) {
                        validAlternations = -10
                        break
                    }
                    if (prevLum != -1 && abs(lum - prevLum) in 8..40) {
                        validAlternations++
                    }
                }
                prevLum = lum
            }

            if (validAlternations >= 5) {
                var exactY = y
                while (exactY > minY) {
                    val p0 = bitmap.getPixel((cellW * 0.82f).toInt(), exactY - 1)
                    val p1 = bitmap.getPixel(cellW + (cellW * 0.82f).toInt(), exactY - 1)
                    val l0 = (Color.red(p0) * 299 + Color.green(p0) * 587 + Color.blue(p0) * 114) / 1000
                    val l1 = (Color.red(p1) * 299 + Color.green(p1) * 587 + Color.blue(p1) * 114) / 1000
                    val diff = abs(l0 - l1)
                    if ((isChessCom && diff >= 35) || (!isChessCom && diff in 8..42 && l0 in 18..88)) {
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

    fun scanPresence(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int,
        isChessCom: Boolean = false
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

    fun scanFullBoardFromScreen(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int,
        isChessCom: Boolean = false
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
                result[r][c] = if (isChessCom) {
                    classifyChessComCell(pixels, size, c * cellSize, r * cellSize, cellSize)
                } else {
                    classifyDuolingoCell(pixels, size, c * cellSize, r * cellSize, cellSize, r, c)
                }
            }
        }

        // Если обнаружена полная стартовая расстановка (все 16 фигур сверху и 16 снизу, центр пуст) —
        // подставляем идеальные начальные фигуры (чтобы в начале партии 100% не перепутать ни одну фигуру)
        if (isStandardInitialPresence(result)) {
            val topIsBlack = result[0].count { it.isLowerCase() } >= 6
            val stdTop = charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')
            val stdBot = charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
            for (c in 0..7) {
                result[0][c] = if (topIsBlack) stdTop[c] else stdBot[c].lowercaseChar()
                result[1][c] = if (topIsBlack) 'p' else 'P'
                for (r in 2..5) result[r][c] = '.'
                result[6][c] = if (topIsBlack) 'P' else 'p'
                result[7][c] = if (topIsBlack) stdBot[c] else stdTop[c]
            }
        }

        return result
    }

    private fun isStandardInitialPresence(grid: Array<CharArray>): Boolean {
        for (c in 0..7) {
            if (grid[0][c] == '.' || grid[1][c] == '.' || grid[6][c] == '.' || grid[7][c] == '.') return false
            for (r in 2..5) {
                if (grid[r][c] != '.') return false
            }
        }
        return true
    }

    // Точный классификатор специально для классических фигур и зелено-бежевой доски Chess.com
    private fun classifyChessComCell(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float
    ): Char {
        val N = 28
        var whiteBodyPixels = 0
        var blackBodyPixels = 0
        var darkOutlinePixels = 0
        var fg = 0
        var topFg = 0      // gy 3..8
        var upperMidFg = 0 // gy 9..14
        var lowerMidFg = 0 // gy 15..20
        var baseFg = 0     // gy 21..25
        var minGy = N
        var minGx = N
        var maxGx = 0
        var topRowPeaks = 0

        // Определяем фоновый цвет клетки по 4 углам (бежевый, зелёный или жёлтый подсветки)
        val cornerOffsets = arrayOf(2 to 25, 25 to 25, 25 to 2, 2 to 18)
        var bgR = 0; var bgG = 0; var bgB = 0
        for ((cx, cy) in cornerOffsets) {
            val px = (cellX + (cx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val py = (cellY + (cy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val c = pixels[py * stride + px]
            bgR += Color.red(c); bgG += Color.green(c); bgB += Color.blue(c)
        }
        bgR /= 4; bgG /= 4; bgB /= 4

        for (gy in 3 until N - 2) {
            var rowTransitions = 0
            var prevIsFg = false
            for (gx in 4 until N - 4) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                val sat = max(r, max(g, b)) - min(r, min(g, b))

                // Проверяем отличие пикселя от фона клетки (бежевого, зелёного или жёлтого)
                val diffFromBg = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)

                // Белая фигура в Chess.com: чисто-белая внутренность (r>242, g>242, b>238, sat<=14)
                val isPureWhiteFill = r >= 242 && g >= 242 && b >= 236 && sat <= 15
                // Чёрная фигура или контур: тёмно-серый нейтральный цвет (lum < 105, sat <= 22)
                val isDarkNeutral = lum <= 105 && sat <= 24 && (g - r) <= 14

                val isPiecePixel = (isPureWhiteFill && diffFromBg >= 24) || (isDarkNeutral && diffFromBg >= 45)

                if (isPiecePixel) {
                    fg++
                    if (isPureWhiteFill) {
                        whiteBodyPixels++
                    } else if (lum in 48..98) {
                        blackBodyPixels++
                    } else if (lum < 48) {
                        darkOutlinePixels++
                    }

                    if (gy < minGy) minGy = gy
                    if (gx < minGx) minGx = gx
                    if (gx > maxGx) maxGx = gx

                    when (gy) {
                        in 3..8 -> topFg++
                        in 9..14 -> upperMidFg++
                        in 15..20 -> lowerMidFg++
                        in 21..25 -> baseFg++
                    }
                    if (!prevIsFg) rowTransitions++
                }
                prevIsFg = isPiecePixel
            }
            if (gy in 5..10 && rowTransitions >= 2) topRowPeaks += rowTransitions
        }

        // Если в клетке нет широкого основания фигуры — клетка пустая (отсекает точки возможных ходов и координаты)
        if (fg < 38 || baseFg < 10 || (minGy > 11)) return '.'

        val isWhitePiece = whiteBodyPixels >= 18 && whiteBodyPixels >= blackBodyPixels * 0.45f
        val width = maxGx - minGx + 1

        // Классификация типа фигуры Chess.com по пропорциям силуэта:
        val pieceChar = when {
            // Пешка: самая низкая (minGy >= 7) и узкая в верхней части (topFg мал)
            minGy >= 7 && topFg <= 14 && fg < 175 -> 'p'
            // Король: самый высокий (крест на макушке minGy <= 4) и массивный вверху
            minGy <= 4 && topFg >= 18 && fg >= 165 -> 'k'
            // Ферзь: широкая корона с несколькими зубцами (topRowPeaks >= 5)
            topRowPeaks >= 5 && width >= 13 && fg >= 150 -> 'q'
            // Ладья: плоская башня средней высоты, одинаковая ширина в середине и сверху, без высокой макушки
            minGy in 5..7 && topFg in 10..28 && upperMidFg >= 42 && topRowPeaks <= 4 -> 'r'
            // Конь: широкая голова в верхней трети (upperMidFg большой) при умеренной общей площади
            minGy in 4..6 && upperMidFg >= 46 && fg in 130..210 -> 'n'
            // Слон: заострённый купол (узкий сверху topFg <= 22, расширяется книзу)
            minGy in 4..6 && topFg <= 24 -> 'b'
            // Запасное правило по размеру:
            fg < 140 -> 'p'
            fg > 195 -> 'q'
            else -> 'n'
        }

        return if (isWhitePiece) pieceChar.uppercaseChar() else pieceChar
    }

    // Классификатор для Duolingo
    private fun classifyDuolingoCell(
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
        var topFg = 0
        var upperMidFg = 0
        var lowerFg = 0
        var minGy = N
        var maxGx = 0
        var minGx = N
        var topRowPeaks = 0

        for (gy in 3 until N - 2) {
            var rowTransitions = 0
            var prevIsFg = false
            for (gx in 4 until N - 4) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

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

        val pieceChar = when {
            minGy >= 7 && fg < 105 -> 'p'
            minGy <= 4 && fg >= 125 -> 'k'
            topRowPeaks >= 4 && fg >= 105 -> 'q'
            minGy in 5..7 && width >= 11 && topFg < 18 && upperMidFg >= 34 -> 'r'
            minGy <= 5 && width <= 11 -> 'b'
            minGy in 5..6 && fg in 85..135 -> 'n'
            fg < 95 -> 'p'
            fg > 135 -> 'q'
            else -> 'r'
        }

        return if (isWhite) pieceChar.uppercaseChar() else pieceChar
    }
}
