package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object BoardRecognizer {

    private data class CellFeatures(
        val occupied: Boolean,
        val isWhite: Boolean,
        val relHeight: Float,     // Высота фигуры относительно размера клетки (0.0 .. 1.0)
        val relWidth: Float,      // Ширина фигуры относительно размера клетки
        val fillRatio: Float,     // Плотность заполнения внутри рамки фигуры
        val topWidthRatio: Float, // Отношение ширины верхушки (верхние 25%) к ширине базы
        val neckWidthRatio: Float,// Отношение ширины "шеи" (35%-55% высоты) к ширине базы
        val topPeaks: Int,        // Число зубцов/пиков в верхней части (корона ферзя/ладьи)
        val centerTopMass: Float, // Масса по центру самой макушки (крест короля / купол слона)
        val asymmetry: Float      // Горизонтальная несимметричность (голова коня)
    )

    // Точный поиск верхнего края доски по максимальному шахматному контрасту 8x8
    fun findBoardTopY(bitmap: Bitmap, expectedTopY: Int, isChessCom: Boolean): Int {
        val w = bitmap.width
        val h = bitmap.height
        val cellW = w / 8
        val minY = (h * 0.18f).toInt().coerceAtLeast(10)
        val maxY = (h * 0.52f).toInt().coerceAtMost(h - w - 10)

        var bestY = expectedTopY
        var bestScore = -1

        for (y in minY..maxY step 4) {
            var score = 0
            // Проверяем горизонтальные чередования клеток на 1-м, 4-м и 8-м рядах доски
            for (rowCheck in intArrayOf(0, 3, 7)) {
                val ry = y + rowCheck * cellW + (cellW * 0.12f).toInt()
                if (ry >= h - 10) continue
                var prevLum = -1
                for (c in 0..7) {
                    val rx = c * cellW + (cellW * 0.15f).toInt()
                    val p = bitmap.getPixel(rx.coerceIn(0, w - 1), ry.coerceIn(0, h - 1))
                    val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    if (prevLum != -1) {
                        val diff = abs(lum - prevLum)
                        if (isChessCom && diff in 25..160) score += 2
                        else if (!isChessCom && diff in 8..55 && lum in 15..95) score += 2
                        else score -= 3
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

        // Точная доводка вверх до пикселя границы начала 1-го ряда
        var exactY = bestY
        while (exactY > minY) {
            val p0 = bitmap.getPixel((cellW * 0.15f).toInt(), exactY - 1)
            val p1 = bitmap.getPixel(cellW + (cellW * 0.15f).toInt(), exactY - 1)
            val l0 = (Color.red(p0) * 299 + Color.green(p0) * 587 + Color.blue(p0) * 114) / 1000
            val l1 = (Color.red(p1) * 299 + Color.green(p1) * 587 + Color.blue(p1) * 114) / 1000
            val diff = abs(l0 - l1)
            if ((isChessCom && diff >= 25) || (!isChessCom && diff in 8..55 && l0 in 15..95)) {
                exactY--
            } else {
                break
            }
        }
        return exactY
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

        val features = Array(8) { r ->
            Array(8) { c ->
                extractCellFeatures(pixels, size, c * cellSize, r * cellSize, cellSize, isChessCom)
            }
        }

        val result = Array(8) { CharArray(8) { '.' } }

        // 1. Проверяем, не начальная ли это расстановка (все 16 фигур сверху и снизу, центр пуст)
        var topOccupied = 0
        var botOccupied = 0
        var midOccupied = 0
        for (c in 0..7) {
            if (features[0][c].occupied) topOccupied++
            if (features[1][c].occupied) topOccupied++
            if (features[6][c].occupied) botOccupied++
            if (features[7][c].occupied) botOccupied++
            for (r in 2..5) if (features[r][c].occupied) midOccupied++
        }

        if (topOccupied == 16 && botOccupied == 16 && midOccupied == 0) {
            val topWhiteVotes = (0..7).count { features[0][it].isWhite } + (0..7).count { features[1][it].isWhite }
            val topIsBlack = topWhiteVotes < 8
            val stdBack = charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')
            for (c in 0..7) {
                result[0][c] = if (topIsBlack) stdBack[c] else stdBack[c].uppercaseChar()
                result[1][c] = if (topIsBlack) 'p' else 'P'
                for (r in 2..5) result[r][c] = '.'
                result[6][c] = if (topIsBlack) 'P' else 'p'
                result[7][c] = if (topIsBlack) stdBack[c].uppercaseChar() else stdBack[c]
            }
            return result
        }

        // 2. Классифицируем каждую занятую клетку по её собственным пропорциям (независимо от сдвига сетки!)
        for (r in 0..7) {
            for (c in 0..7) {
                val f = features[r][c]
                if (!f.occupied) {
                    result[r][c] = '.'
                    continue
                }
                val rawType = classifyByNormalizedShape(f, r)
                result[r][c] = if (f.isWhite) rawType.uppercaseChar() else rawType
            }
        }

        // 3. Глобальная калибровка по всем фигурам на доске:
        // Находим самую высокую фигуру каждого цвета (Король) и проверяем отсутствие пешек на 1/8 горизонталях
        enforceChessRules(result, features)

        return result
    }

    // Извлекает чистую маску фигуры путём сравнения каждого пикселя с собственным фоном этой клетки
    private fun extractCellFeatures(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        isChessCom: Boolean
    ): CellFeatures {
        val N = 32
        // Сэмплируем собственный цвет фона клетки по точкам у левого и нижнего края (вдали от фигуры и правого верхнего бейджа)
        val bgCoords = arrayOf(
            2 to 10, 2 to 16, 2 to 22,
            29 to 16, 29 to 22,
            6 to 29, 16 to 29, 25 to 29
        )
        val rSamples = IntArray(bgCoords.size)
        val gSamples = IntArray(bgCoords.size)
        val bSamples = IntArray(bgCoords.size)

        for (i in bgCoords.indices) {
            val (gx, gy) = bgCoords[i]
            val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val c = pixels[py * stride + px]
            rSamples[i] = Color.red(c)
            gSamples[i] = Color.green(c)
            bSamples[i] = Color.blue(c)
        }
        rSamples.sort(); gSamples.sort(); bSamples.sort()
        // Медианный цвет фона этой конкретной клетки (устойчив даже если клетка подсвечена жёлтым/зелёным/голубым!)
        val bgR = rSamples[rSamples.size / 2]
        val bgG = gSamples[gSamples.size / 2]
        val bgB = bSamples[bSamples.size / 2]
        val bgLum = (bgR * 299 + bgG * 587 + bgB * 114) / 1000

        val mask = Array(N) { BooleanArray(N) }
        var fgCount = 0
        var brightPixels = 0
        var darkPixels = 0
        var lumSum = 0

        var minGy = N; var maxGy = -1
        var minGx = N; var maxGx = -1

        // Сканируем центральную область клетки (gx 4..27, gy 3..28)
        for (gy in 3..28) {
            for (gx in 4..27) {
                // Игнорируем маленький правый верхний угол (gx >= 20, gy <= 9), где висит наш мини-значок
                if (gx >= 20 && gy <= 9) continue

                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                // Пропускаем ярко-зелёный цвет нашей стрелки подсказки и голубой цвет сетки
                if (g > 210 && g > r + 90 && b < 120) continue
                if (b > 210 && g > 195 && r < 40) continue

                val dr = r - bgR
                val dg = g - bgG
                val db = b - bgB
                val colorDist = sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                val sat = max(r, max(g, b)) - min(r, min(g, b))

                var isFg = false
                if (isChessCom) {
                    // В Chess.com фигуры всегда имеют низкую насыщенность (белые или тёмно-серые),
                    // либо сильно отличаются по яркости от цветного фона клетки
                    val isPureWhite = r >= 232 && g >= 232 && b >= 225 && sat <= 22
                    val isCharcoalDark = lum <= 112 && sat <= 30
                    if ((isPureWhite && colorDist >= 22f) || (isCharcoalDark && colorDist >= 42f) || colorDist >= 68f) {
                        isFg = true
                    }
                } else {
                    // В Duolingo фон тёмный (lum 25..65), любые элементы фигуры заметно отличаются от фона
                    if (colorDist >= 32f && lum >= bgLum + 16) {
                        isFg = true
                    }
                }

                if (isFg) {
                    mask[gy][gx] = true
                    fgCount++
                    lumSum += lum
                    if (isChessCom) {
                        if (lum >= 210 && sat <= 25) brightPixels++
                        else if (lum in 45..110) darkPixels++
                    } else {
                        if (lum >= 185) brightPixels++
                        else darkPixels++
                    }
                    if (gy < minGy) minGy = gy
                    if (gy > maxGy) maxGy = gy
                    if (gx < minGx) minGx = gx
                    if (gx > maxGx) maxGx = gx
                }
            }
        }

        // Проверяем наличие широкого основания в нижней части фигуры (отсекает кружки подсказки хода в центре клетки!)
        var bottomPartPixels = 0
        for (gy in 20..28) {
            for (gx in 6..25) {
                if (mask[gy][gx]) bottomPartPixels++
            }
        }

        val boxH = maxGy - minGy + 1
        val boxW = maxGx - minGx + 1

        if (fgCount < 45 || bottomPartPixels < 14 || boxH < 9 || boxW < 7) {
            return CellFeatures(false, false, 0f, 0f, 0f, 0f, 0f, 0, 0f, 0f)
        }

        // Определяем цвет фигуры (Белая или Чёрная):
        val isWhite = if (isChessCom) {
            brightPixels >= 20 && brightPixels >= darkPixels * 0.40f
        } else {
            brightPixels >= 16 && brightPixels >= fgCount * 0.24f
        }

        // Вычисляем ширину фигуры на каждой строке внутри её собственного Bounding Box (minGy .. maxGy)
        val widths = IntArray(boxH)
        var asymSum = 0f
        val boxCenterX = (minGx + maxGx) / 2f

        for (i in 0 until boxH) {
            val gy = minGy + i
            var l = N; var r = -1
            for (gx in minGx..maxGx) {
                if (mask[gy][gx]) {
                    if (gx < l) l = gx
                    if (gx > r) r = gx
                }
            }
            if (r >= l) {
                widths[i] = r - l + 1
                val rowCenter = (l + r) / 2f
                asymSum += abs(rowCenter - boxCenterX)
            }
        }

        // Базовая ширина основания (нижние 25% высоты самой фигуры)
        val baseStart = (boxH * 0.72f).toInt().coerceIn(0, boxH - 1)
        var baseWidth = 1
        for (i in baseStart until boxH) {
            if (widths[i] > baseWidth) baseWidth = widths[i]
        }

        // Ширина верхушки ("головы/короны" — 12%..32% от верха самой фигуры)
        val headStart = (boxH * 0.12f).toInt().coerceIn(0, boxH - 1)
        val headEnd = (boxH * 0.32f).toInt().coerceIn(headStart, boxH - 1)
        var headWidth = 0
        for (i in headStart..headEnd) {
            if (widths[i] > headWidth) headWidth = widths[i]
        }

        // Ширина "шеи" (36%..55% от верха самой фигуры)
        val neckStart = (boxH * 0.36f).toInt().coerceIn(0, boxH - 1)
        val neckEnd = (boxH * 0.55f).toInt().coerceIn(neckStart, boxH - 1)
        var neckWidth = baseWidth
        for (i in neckStart..neckEnd) {
            if (widths[i] in 2 until neckWidth) neckWidth = widths[i]
        }

        // Считаем число зубцов в верхней четверти фигуры (у Ферзя и Ладьи несколько зубцов)
        var topPeaks = 0
        val peakEnd = (boxH * 0.28f).toInt().coerceIn(1, boxH - 1)
        for (i in 0..peakEnd) {
            val gy = minGy + i
            var transitions = 0
            var prev = false
            for (gx in minGx..maxGx) {
                val cur = mask[gy][gx]
                if (cur && !prev) transitions++
                prev = cur
            }
            if (transitions >= 2) topPeaks += transitions
        }

        // Масса на самой макушке по центру (крест Короля / острие Слона)
        val top15End = (boxH * 0.15f).toInt().coerceIn(0, boxH - 1)
        var topCenterCount = 0
        for (i in 0..top15End) {
            if (widths[i] in 1..(baseWidth / 2 + 1)) topCenterCount++
        }

        val relH = boxH / 25f
        val relW = baseWidth / 22f
        val fillRatio = fgCount.toFloat() / (boxH * boxW).coerceAtLeast(1)
        val topRatio = headWidth.toFloat() / baseWidth.coerceAtLeast(1)
        val neckRatio = neckWidth.toFloat() / baseWidth.coerceAtLeast(1)
        val asymmetry = asymSum / boxH.coerceAtLeast(1)

        return CellFeatures(
            occupied = true,
            isWhite = isWhite,
            relHeight = relH,
            relWidth = relW,
            fillRatio = fillRatio,
            topWidthRatio = topRatio,
            neckWidthRatio = neckRatio,
            topPeaks = topPeaks,
            centerTopMass = topCenterCount.toFloat(),
            asymmetry = asymmetry
        )
    }

    private fun classifyByNormalizedShape(f: CellFeatures, screenRow: Int): Char {
        val isBackRank = (screenRow == 0 || screenRow == 7)

        // 1. ПЕШКА: самая компактная по высоте (relHeight < 0.74) и с выраженной узкой шеей (neckWidthRatio < 0.58)
        if (!isBackRank && f.relHeight < 0.73f && f.topWidthRatio < 0.72f && f.neckWidthRatio < 0.62f) {
            return 'p'
        }

        // 2. ЛАДЬЯ: широкая плоская башня сверху (topWidthRatio >= 0.72) и прямая широкая шея без сильной талии (neckWidthRatio >= 0.62), умеренная высота
        if (f.relHeight in 0.68f..0.88f && f.topWidthRatio >= 0.70f && f.neckWidthRatio >= 0.60f && f.centerTopMass <= 1.5f) {
            return 'r'
        }

        // 3. КОРОЛЬ: самая высокая фигура (relHeight >= 0.86) с узким крестом на макушке (centerTopMass >= 1.5)
        if (f.relHeight >= 0.86f && f.centerTopMass >= 1.5f && f.topWidthRatio >= 0.68f) {
            return 'k'
        }

        // 4. ФЕРЗЬ: высокая и широкая корона с зубцами сверху (topPeaks >= 4 или topWidthRatio >= 0.80 при большой высоте)
        if (f.relHeight >= 0.80f && (f.topPeaks >= 4 || f.topWidthRatio >= 0.78f)) {
            return 'q'
        }

        // 5. КОНЬ: заметная горизонтальная асимметрия головы (asymmetry >= 0.85) и плотная широкая шея (neckWidthRatio >= 0.62)
        if (f.asymmetry >= 0.82f || (f.neckWidthRatio >= 0.62f && f.topWidthRatio < 0.74f)) {
            return 'n'
        }

        // 6. СЛОН: высокая фигура (relHeight >= 0.76), но с узкой заострённой верхушкой (topWidthRatio < 0.68) и узкой шеей
        if (f.relHeight >= 0.75f && f.topWidthRatio < 0.70f) {
            return 'b'
        }

        if (!isBackRank && f.relHeight < 0.77f) return 'p'
        return if (f.topWidthRatio >= 0.72f) 'r' else 'n'
    }

    private fun enforceChessRules(grid: Array<CharArray>, features: Array<Array<CellFeatures>>) {
        // 1. Никаких пешек на 0-м и 7-м ряду!
        for (r in intArrayOf(0, 7)) {
            for (c in 0..7) {
                val p = grid[r][c]
                if (p == 'P' || p == 'p') {
                    val f = features[r][c]
                    val rep = if (f.topWidthRatio >= 0.70f) 'r' else if (f.asymmetry >= 0.8f) 'n' else 'b'
                    grid[r][c] = if (p.isUpperCase()) rep.uppercaseChar() else rep
                }
            }
        }

        // 2. Ровно один Белый Король ('K') и ровно один Чёрный Король ('k')
        for (whiteSide in booleanArrayOf(true, false)) {
            val kChar = if (whiteSide) 'K' else 'k'
            val allPieces = mutableListOf<Pair<Int, Int>>()
            val kingPieces = mutableListOf<Pair<Int, Int>>()

            for (r in 0..7) {
                for (c in 0..7) {
                    val p = grid[r][c]
                    if (p != '.' && p.isUpperCase() == whiteSide) {
                        allPieces.add(r to c)
                        if (p == kChar) kingPieces.add(r to c)
                    }
                }
            }

            if (allPieces.isEmpty()) continue

            // Оценка "королевскости": максимальная высота + наличие креста сверху
            fun kingRank(pos: Pair<Int, Int>): Float {
                val f = features[pos.first][pos.second]
                return f.relHeight * 100f + f.centerTopMass * 8f - f.topPeaks * 2f
            }

            if (kingPieces.size > 1) {
                val bestK = kingPieces.maxByOrNull { kingRank(it) }!!
                for (pos in kingPieces) {
                    if (pos != bestK) {
                        val f = features[pos.first][pos.second]
                        val sub = if (f.topPeaks >= 3 || f.topWidthRatio >= 0.75f) 'q'
                        else if (f.neckWidthRatio >= 0.60f) 'r' else 'b'
                        grid[pos.first][pos.second] = if (whiteSide) sub.uppercaseChar() else sub
                    }
                }
            } else if (kingPieces.isEmpty()) {
                val bestK = allPieces.maxByOrNull { kingRank(it) }!!
                grid[bestK.first][bestK.second] = kChar
            }
        }
    }
}
