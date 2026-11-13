package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    private data class CellScan(
        var piece: Char,
        val isWhite: Boolean,
        val kingScore: Int,
        val rookScore: Int,
        val queenScore: Int
    )

    fun findBoardTopY(bitmap: Bitmap, expectedTopY: Int, isChessCom: Boolean): Int {
        val w = bitmap.width
        val h = bitmap.height
        val cellW = w / 8
        val minY = (h * 0.22f).toInt().coerceAtLeast(10)
        val maxY = (h * 0.56f).toInt().coerceAtMost(h - w - 10)

        for (y in minY..maxY step 3) {
            var validAlternations = 0
            var prevLum = -1
            for (c in 0..7) {
                val sampleX = c * cellW + (cellW * 0.84f).toInt()
                val sampleY = y + (cellW * 0.14f).toInt()
                val pixel = bitmap.getPixel(sampleX.coerceIn(0, w - 1), sampleY.coerceIn(0, h - 1))
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                if (isChessCom) {
                    val isLightSq = lum > 170 && r > 175 && g > 175
                    val isGreenSq = lum in 85..175 && g > r + 10
                    val isBlueHighlight = lum in 130..225 && b > r + 20
                    if (!isLightSq && !isGreenSq && !isBlueHighlight) {
                        validAlternations = -10
                        break
                    }
                    if (prevLum != -1 && abs(lum - prevLum) >= 28) {
                        validAlternations++
                    }
                } else {
                    if (lum !in 18..92 || r > b + 15) {
                        validAlternations = -10
                        break
                    }
                    if (prevLum != -1 && abs(lum - prevLum) in 8..45) {
                        validAlternations++
                    }
                }
                prevLum = lum
            }

            if (validAlternations >= 5) {
                var exactY = y
                while (exactY > minY) {
                    val p0 = bitmap.getPixel((cellW * 0.84f).toInt(), exactY - 1)
                    val p1 = bitmap.getPixel(cellW + (cellW * 0.84f).toInt(), exactY - 1)
                    val l0 = (Color.red(p0) * 299 + Color.green(p0) * 587 + Color.blue(p0) * 114) / 1000
                    val l1 = (Color.red(p1) * 299 + Color.green(p1) * 587 + Color.blue(p1) * 114) / 1000
                    val diff = abs(l0 - l1)
                    if ((isChessCom && diff >= 28) || (!isChessCom && diff in 8..45 && l0 in 18..92)) {
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

        val scans = Array(8) { Array(8) { CellScan('.', false, 0, 0, 0) } }
        val result = Array(8) { CharArray(8) { '.' } }

        for (r in 0..7) {
            for (c in 0..7) {
                val cellScan = if (isChessCom) {
                    classifyChessComCell(pixels, size, c * cellSize, r * cellSize, cellSize, r)
                } else {
                    classifyDuolingoCell(pixels, size, c * cellSize, r * cellSize, cellSize, r, c)
                }
                scans[r][c] = cellScan
                result[r][c] = cellScan.piece
            }
        }

        // Если обнаружена стандартная начальная расстановка 32 фигур — ставим идеальную стартовую доску
        if (isStandardInitialPresence(result)) {
            val topIsBlack = result[0].count { it.isLowerCase() } >= 5
            val stdTop = charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')
            val stdBot = charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
            for (c in 0..7) {
                result[0][c] = if (topIsBlack) stdTop[c] else stdBot[c].lowercaseChar()
                result[1][c] = if (topIsBlack) 'p' else 'P'
                for (r in 2..5) result[r][c] = '.'
                result[6][c] = if (topIsBlack) 'P' else 'p'
                result[7][c] = if (topIsBlack) stdBot[c] else stdTop[c]
            }
            return result
        }

        // Шахматная валидация: ровно 1 белый и 1 чёрный Король, никаких пешек на 1-й и 8-й линиях!
        sanitizeScannedBoard(result, scans)
        return result
    }

    private fun sanitizeScannedBoard(grid: Array<CharArray>, scans: Array<Array<CellScan>>) {
        // 1. На 0-й и 7-й строках экрана (1-я и 8-я горизонтали) НЕ МОЖЕТ БЫТЬ ПЕШЕК
        for (r in intArrayOf(0, 7)) {
            for (c in 0..7) {
                val p = grid[r][c]
                if (p == 'P' || p == 'p') {
                    val s = scans[r][c]
                    val rep = if (s.rookScore >= s.queenScore) 'r' else 'q'
                    grid[r][c] = if (p.isUpperCase()) rep.uppercaseChar() else rep
                }
            }
        }

        // 2. Гарантируем ровно одного Белого Короля ('K') и ровно одного Чёрного Короля ('k')
        for (whiteSide in booleanArrayOf(true, false)) {
            val kChar = if (whiteSide) 'K' else 'k'
            val candidates = mutableListOf<Pair<Int, Int>>()
            var bestAnyPos: Pair<Int, Int>? = null
            var bestAnyKingScore = -9999

            for (r in 0..7) {
                for (c in 0..7) {
                    val p = grid[r][c]
                    if (p != '.' && p.isUpperCase() == whiteSide) {
                        val ks = scans[r][c].kingScore
                        if (ks > bestAnyKingScore) {
                            bestAnyKingScore = ks
                            bestAnyPos = r to c
                        }
                        if (p == kChar) {
                            candidates.add(r to c)
                        }
                    }
                }
            }

            if (candidates.size > 1) {
                // Оставляем Королём только клетку с максимальным kingScore, остальные переводим в Ферзя/Ладью/Коня
                val trueKing = candidates.maxByOrNull { (r, c) -> scans[r][c].kingScore }!!
                for ((r, c) in candidates) {
                    if (r != trueKing.first || c != trueKing.second) {
                        val s = scans[r][c]
                        val fallback = when {
                            s.queenScore >= s.rookScore -> 'q'
                            s.rookScore > 30 -> 'r'
                            else -> 'n'
                        }
                        grid[r][c] = if (whiteSide) fallback.uppercaseChar() else fallback
                    }
                }
            } else if (candidates.isEmpty() && bestAnyPos != null) {
                // Если король был случайно распознан как ферзь или ладья — назначаем лучшего кандидата Королём
                val (kr, kc) = bestAnyPos
                grid[kr][kc] = kChar
            }
        }
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

    // Улучшенный классификатор Chess.com: учитывает полный силуэт (заливка + контур) и профиль ширины по рядам
    private fun classifyChessComCell(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        screenRow: Int
    ): CellScan {
        val N = 28
        // Берём фон по 4 точкам вокруг центра по краям (избегая координат в углах)
        val bgSamples = arrayOf(3 to 14, 24 to 14, 24 to 6, 3 to 20)
        var bgR = 0; var bgG = 0; var bgB = 0
        for ((cx, cy) in bgSamples) {
            val px = (cellX + (cx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val py = (cellY + (cy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val c = pixels[py * stride + px]
            bgR += Color.red(c); bgG += Color.green(c); bgB += Color.blue(c)
        }
        bgR /= 4; bgG /= 4; bgB /= 4

        var whiteInterior = 0
        var blackInterior = 0
        var fg = 0
        var minGy = N
        val rowWidths = IntArray(N)
        val rowPeaks = IntArray(N)

        for (gy in 2 until N - 2) {
            var leftGx = N
            var rightGx = -1
            var peaks = 0
            var prevFg = false

            for (gx in 4 until N - 4) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                // Игнорируем нашу ярко-зелёную стрелку и оранжевый пунктир
                if (g > 200 && g > r + 80) continue
                if (r > 220 && g in 140..190 && b < 40) continue

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                val sat = max(r, max(g, b)) - min(r, min(g, b))
                val diffBg = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)

                val isWhiteFill = r >= 238 && g >= 238 && b >= 230 && sat <= 18
                val isDarkPieceOrOutline = lum <= 110 && sat <= 28 && (g - r) <= 15

                val isFg = (isWhiteFill && diffBg >= 22) || (isDarkPieceOrOutline && diffBg >= 48)
                if (isFg) {
                    fg++
                    if (isWhiteFill) whiteInterior++
                    else if (lum in 45..102) blackInterior++

                    if (gy < minGy) minGy = gy
                    if (gx < leftGx) leftGx = gx
                    if (gx > rightGx) rightGx = gx
                    if (!prevFg) peaks++
                }
                prevFg = isFg
            }
            if (rightGx >= leftGx) {
                rowWidths[gy] = rightGx - leftGx + 1
            }
            rowPeaks[gy] = peaks
        }

        val baseWidth = max(rowWidths[22], max(rowWidths[23], rowWidths[24]))
        // Отсекаем пустые клетки и кружки подсказок хода (у кружка хода внизу baseWidth == 0)
        if (fg < 42 || baseWidth < 9 || minGy > 11) {
            return CellScan('.', false, 0, 0, 0)
        }

        val isWhite = whiteInterior >= 16 && whiteInterior >= blackInterior * 0.42f

        // Измеряем ширину силуэта на разных высотах:
        // headW (верхушка 5..8), neckW (шея 9..11), midW (живот 13..16)
        val crownW = max(rowWidths[3], max(rowWidths[4], rowWidths[5]))
        val headW = max(rowWidths[6], max(rowWidths[7], rowWidths[8]))
        val neckW = min(rowWidths[10].coerceAtLeast(1), rowWidths[11].coerceAtLeast(1))
        val midW = max(rowWidths[14], max(rowWidths[15], rowWidths[16]))
        val totalPeaksTop = rowPeaks[5] + rowPeaks[6] + rowPeaks[7] + rowPeaks[8]

        val kingScore = (if (minGy <= 4) 60 else 0) + (if (crownW in 3..9) 35 else 0) + fg / 3
        val queenScore = totalPeaksTop * 18 + headW * 4 + fg / 4
        val rookScore = headW * 6 + neckW * 5 - (if (minGy <= 4) 40 else 0)

        val pieceChar = when {
            // Пешка: низкая (minGy >= 7), круглая голова уже основания, узкая шея, и НЕ на 1/8 горизонтали!
            screenRow in 1..6 && minGy >= 7 && headW <= 11 && neckW <= 9 && fg < 170 -> 'p'

            // Король: самый высокий (minGy <= 4) за счёт креста сверху (узкий крест crownW 3..9 над широкой головой)
            minGy <= 4 && crownW in 3..10 && midW >= 12 && fg >= 155 -> 'k'

            // Ферзь: широкая корона с несколькими зубцами (totalPeaksTop >= 6 или широкая верхушка headW >= 14)
            (totalPeaksTop >= 6 || headW >= 14) && midW >= 12 && fg >= 145 -> 'q'

            // Ладья: начинается на высоте 5..7, широкая плоская башня сверху (headW >= 11) и широкая прямая шея (neckW >= 9)
            minGy in 5..8 && headW >= 10 && neckW >= 9 && abs(headW - midW) <= 3 -> 'r'

            // Конь: высокая и широкая несимметричная голова/шея (neckW >= 10, midW >= 12)
            minGy in 4..7 && neckW >= 10 && midW >= 11 -> 'n'

            // Слон: высокий купол с узкой макушкой (headW <= 10) и расширением к середине
            minGy in 4..7 && headW <= 10 && midW >= headW + 2 -> 'b'

            // Если это 1-я или 8-я горизонталь — пешкой быть не может:
            screenRow == 0 || screenRow == 7 -> if (headW >= 11) 'r' else 'n'

            fg < 150 -> 'p'
            else -> 'r'
        }

        val finalChar = if (isWhite) pieceChar.uppercaseChar() else pieceChar
        return CellScan(finalChar, isWhite, kingScore, rookScore, queenScore)
    }

    // Улучшенный классификатор Duolingo: стабилен при игре и Белыми, и Чёрными
    private fun classifyDuolingoCell(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        row: Int,
        col: Int
    ): CellScan {
        val N = 24
        var bgLumSum = 0; var bgCnt = 0
        val corners = arrayOf(2 to 21, 21 to 21, 21 to 3, 2 to 18)
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
        var veryBrightPixels = 0 // Пиксели ярче 205 (есть только у БЕЛЫХ фигур в Duolingo!)
        var minGy = N
        val rowWidths = IntArray(N)
        var topRowPeaks = 0

        for (gy in 2 until N - 2) {
            var leftGx = N
            var rightGx = -1
            var transitions = 0
            var prevIsFg = false

            for (gx in 4 until N - 4) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                if (g > 185 && g > r + 70) continue
                if (b > 200 && g > 190 && r < 45) continue

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                var isPiece = false
                if (lum >= 195 && r >= 170) {
                    isPiece = true
                    veryBrightPixels++
                } else if (lum >= max(72, bgLum + 20) && b >= r - 5) {
                    isPiece = true
                }

                if (isPiece) {
                    fg++
                    if (gy < minGy) minGy = gy
                    if (gx < leftGx) leftGx = gx
                    if (gx > rightGx) rightGx = gx
                    if (!prevIsFg) transitions++
                }
                prevIsFg = isPiece
            }
            if (rightGx >= leftGx) rowWidths[gy] = rightGx - leftGx + 1
            if (gy in 4..8 && transitions >= 2) topRowPeaks += transitions
        }

        val baseW = max(rowWidths[18], max(rowWidths[19], rowWidths[20]))
        if (fg < 26 || baseW < 6 || minGy > 11) {
            return CellScan('.', false, 0, 0, 0)
        }

        // В Duolingo белые фигуры внутри ярко-белые (lum > 195), а чёрные — тёмно-синие с голубым контуром
        val isWhite = veryBrightPixels >= 14 && veryBrightPixels >= fg * 0.28f

        val crownW = max(rowWidths[3], rowWidths[4])
        val headW = max(rowWidths[6], rowWidths[7])
        val neckW = min(rowWidths[9].coerceAtLeast(1), rowWidths[10].coerceAtLeast(1))
        val midW = max(rowWidths[12], rowWidths[13])

        val kingScore = (if (minGy <= 3) 70 else 0) + (if (crownW in 2..7) 30 else 0) + fg / 2
        val queenScore = topRowPeaks * 20 + headW * 4 + fg / 3
        val rookScore = headW * 6 + neckW * 5 - (if (minGy <= 3) 45 else 0)

        val pieceChar = when {
            row in 1..6 && minGy >= 6 && headW <= 9 && neckW <= 7 && fg < 105 -> 'p'
            minGy <= 3 && crownW in 2..8 && fg >= 115 -> 'k'
            (topRowPeaks >= 4 || headW >= 12) && fg >= 105 -> 'q'
            minGy in 4..7 && headW >= 9 && neckW >= 8 && abs(headW - midW) <= 3 -> 'r'
            minGy in 4..6 && headW <= 8 && midW >= headW + 2 -> 'b'
            minGy in 4..6 && neckW >= 8 -> 'n'
            row == 0 || row == 7 -> if (headW >= 9) 'r' else 'n'
            fg < 98 -> 'p'
            else -> 'r'
        }

        val finalChar = if (isWhite) pieceChar.uppercaseChar() else pieceChar
        return CellScan(finalChar, isWhite, kingScore, rookScore, queenScore)
    }
}
