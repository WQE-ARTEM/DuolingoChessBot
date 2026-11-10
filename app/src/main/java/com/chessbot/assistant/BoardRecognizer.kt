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

    /**
     * Автоматическое уточнение верхней границы доски по перепаду яркости клеток.
     */
    fun refineBoardTop(bmp: Bitmap, approxTop: Int, boardSize: Int): Int {
        val searchRadius = (boardSize * 0.025f).toInt().coerceAtLeast(10)
        val minY = (approxTop - searchRadius).coerceAtLeast(10)
        val maxY = (approxTop + searchRadius).coerceAtMost(bmp.height - boardSize - 10)
        val cellW = boardSize / 8f

        var bestY = approxTop
        var bestScore = -1

        for (y in minY..maxY step 2) {
            var edgeContrast = 0
            // Проверяем горизонтальную границу начала 8 клеток верхнего ряда
            for (c in 0..7) {
                val cx = (c * cellW + cellW * 0.12f).toInt().coerceIn(0, bmp.width - 1)
                val pAbove = bmp.getPixel(cx, (y - 4).coerceAtLeast(0))
                val pInside = bmp.getPixel(cx, (y + 6).coerceAtMost(bmp.height - 1))
                val diff = abs(Color.red(pAbove) - Color.red(pInside)) +
                           abs(Color.green(pAbove) - Color.green(pInside)) +
                           abs(Color.blue(pAbove) - Color.blue(pInside))
                edgeContrast += diff
            }
            if (edgeContrast > bestScore) {
                bestScore = edgeContrast
                bestY = y
            }
        }
        return if (bestScore > 90) bestY else approxTop
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
                grid[r][c] = classifyCellSilhouette(bitmap, cellX, cellY, cw, ch)
            }
        }

        // Приводим к стандартной ориентации FEN (row 0 = 8-я горизонталь черных, row 7 = 1-я горизонталь белых)
        val canonicalGrid = Array(8) { CharArray(8) { '.' } }
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                canonicalGrid[r][c] = if (whiteAtBottom) grid[r][c] else grid[7 - r][7 - c]
            }
        }

        normalizeBoardState(canonicalGrid)

        // Возвращаем исправленные метки обратно в экранную сетку для отрисовки на оверлее
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                grid[r][c] = if (whiteAtBottom) canonicalGrid[r][c] else canonicalGrid[7 - r][7 - c]
            }
        }

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

    /**
     * Точный классификатор фигур Duolingo Chess:
     * 1. Вычисляет цвет фона самой клетки по боковым полям (работает даже на зеленых подсвеченных клетках!).
     * 2. Ограничивает скан по высоте до 78% клетки, чтобы короны нижнего ряда НЕ сливались с пешками сверху.
     * 3. Включает затененную правую сторону белых фигур (lum >= 128), сохраняя 100% симметрию R, B, Q, K.
     */
    private fun classifyCellSilhouette(bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int): Char {
        if (x0 < 0 || y0 < 0 || x0 + w > bmp.width || y0 + h > bmp.height || w < 24 || h < 24) return '.'

        // 1. Замеряем эталонный цвет фона данной конкретной клетки по её левому и правому верхним краям
        var bgR = 0; var bgG = 0; var bgB = 0; var bgCount = 0
        val edgeYStart = (h * 0.16f).toInt()
        val edgeYEnd = (h * 0.52f).toInt()
        for (dy in edgeYStart until edgeYEnd step 3) {
            for (dx in listOf((w * 0.06f).toInt(), (w * 0.93f).toInt())) {
                val px = bmp.getPixel(x0 + dx, y0 + dy)
                bgR += Color.red(px)
                bgG += Color.green(px)
                bgB += Color.blue(px)
                bgCount++
            }
        }
        if (bgCount > 0) {
            bgR /= bgCount; bgG /= bgCount; bgB /= bgCount
        }
        val bgLum = (bgR * 299 + bgG * 587 + bgB * 114) / 1000

        // 2. Сканируем внутреннюю зону клетки (строго до 78% высоты, исключая наложение фигур снизу!)
        val xStart = (w * 0.13f).toInt()
        val xEnd = (w * 0.87f).toInt()
        val yStart = (h * 0.05f).toInt()
        val yEnd = (h * 0.78f).toInt()
        val scanW = xEnd - xStart
        val scanH = yEnd - yStart
        if (scanW <= 6 || scanH <= 6) return '.'

        val mask = Array(scanH) { BooleanArray(scanW) }
        var fgPixels = 0
        var brightWhitePixels = 0
        var slateBlackPixels = 0

        for (dy in 0 until scanH) {
            for (dx in 0 until scanW) {
                val px = bmp.getPixel(x0 + xStart + dx, y0 + yStart + dy)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                val distFromBg = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)

                // Пиксель принадлежит фигуре, если он заметно ярче фона клетки и отличается по цвету
                if (distFromBg >= 36 && lum > bgLum + 16) {
                    mask[dy][dx] = true
                    fgPixels++
                    if (lum >= 132) {
                        brightWhitePixels++
                    } else if (lum in 54..131) {
                        slateBlackPixels++
                    }
                }
            }
        }

        val totalArea = scanW * scanH
        val fillRatio = fgPixels.toFloat() / totalArea
        if (fillRatio < 0.11f) return '.' // Клетка пустая

        val isWhite = brightWhitePixels > fgPixels * 0.42f

        // 3. Строим горизонтальные профили ширины wProfile[dy] и центра cxProfile[dy]
        val wProfile = FloatArray(scanH)
        val cxProfile = FloatArray(scanH)
        val leftEdge = FloatArray(scanH)
        var topY = -1

        for (dy in 0 until scanH) {
            var rMin = scanW
            var rMax = -1
            var count = 0
            for (dx in 0 until scanW) {
                if (mask[dy][dx]) {
                    if (dx < rMin) rMin = dx
                    if (dx > rMax) rMax = dx
                    count++
                }
            }
            if (count >= 2 && rMax > rMin) {
                val rowW = (rMax - rMin + 1).toFloat() / scanW
                wProfile[dy] = rowW
                cxProfile[dy] = ((rMin + rMax) * 0.5f) / scanW
                leftEdge[dy] = rMin.toFloat() / scanW
                if (topY == -1 && rowW >= 0.15f) {
                    topY = dy
                }
            }
        }

        if (topY == -1) return '.'
        val topStartFrac = topY.toFloat() / scanH

        // Измеряем максимальную и среднюю ширину в верхней половине клетки (зона головки пешки vs корон фигур)
        val upperBandStart = (scanH * 0.10f).toInt()
        val upperBandEnd = (scanH * 0.54f).toInt()
        var maxUpperW = 0f
        var sumUpperW = 0f
        var upperRows = 0
        for (dy in upperBandStart until upperBandEnd) {
            if (wProfile[dy] > 0f) {
                if (wProfile[dy] > maxUpperW) maxUpperW = wProfile[dy]
                sumUpperW += wProfile[dy]
                upperRows++
            }
        }
        val avgUpperW = if (upperRows > 0) sumUpperW / upperRows else 0f

        // ПРАВИЛО 1: ПЕШКА (P / p)
        // У пешки маленькая круглая головка и узкая шейка: её максимальная ширина в верхней половине < 0.52 от scanW,
        // а начинается она ниже всех остальных фигур (topStartFrac >= 0.16f).
        if (maxUpperW < 0.53f || avgUpperW < 0.41f || topStartFrac >= 0.20f) {
            return if (isWhite) 'P' else 'p'
        }

        // Функции измерения ширины и центра относительно верхушки фигуры topY
        val remH = (scanH - topY).coerceAtLeast(10)
        fun avgWidthRel(f1: Float, f2: Float): Float {
            val y1 = (topY + remH * f1).toInt().coerceIn(0, scanH - 1)
            val y2 = (topY + remH * f2).toInt().coerceIn(y1 + 1, scanH)
            var s = 0f; var c = 0
            for (y in y1 until y2) {
                if (wProfile[y] > 0.05f) { s += wProfile[y]; c++ }
            }
            return if (c > 0) s / c else 0f
        }

        fun maxWidthRel(f1: Float, f2: Float): Float {
            val y1 = (topY + remH * f1).toInt().coerceIn(0, scanH - 1)
            val y2 = (topY + remH * f2).toInt().coerceIn(y1 + 1, scanH)
            var m = 0f
            for (y in y1 until y2) if (wProfile[y] > m) m = wProfile[y]
            return m
        }

        fun avgCxRel(f1: Float, f2: Float): Float {
            val y1 = (topY + remH * f1).toInt().coerceIn(0, scanH - 1)
            val y2 = (topY + remH * f2).toInt().coerceIn(y1 + 1, scanH)
            var s = 0f; var c = 0
            for (y in y1 until y2) {
                if (wProfile[y] > 0.10f) { s += cxProfile[y]; c++ }
            }
            return if (c > 0) s / c else 0.5f
        }

        fun avgLeftRel(f1: Float, f2: Float): Float {
            val y1 = (topY + remH * f1).toInt().coerceIn(0, scanH - 1)
            val y2 = (topY + remH * f2).toInt().coerceIn(y1 + 1, scanH)
            var s = 0f; var c = 0
            for (y in y1 until y2) {
                if (wProfile[y] > 0.10f) { s += leftEdge[y]; c++ }
            }
            return if (c > 0) s / c else 0.2f
        }

        // ПРАВИЛО 2: КОНЬ (N / n)
        // Конь — единственная фигура с выступом морды слева (0.20..0.42) и резким вырезом под челюстью слева (0.48..0.68)
        val cxSnout = avgCxRel(0.18f, 0.40f)
        val cxNeck  = avgCxRel(0.46f, 0.68f)
        val leftSnout = avgLeftRel(0.18f, 0.40f)
        val leftJaw   = avgLeftRel(0.48f, 0.68f)

        val centerShiftRight = cxNeck - cxSnout
        val jawCutoutInward = leftJaw - leftSnout

        if (centerShiftRight > 0.045f && jawCutoutInward > 0.065f) {
            return if (isWhite) 'N' else 'n'
        }

        // ПРАВИЛО 3: Симметричные фигуры (R, B, Q, K) по матрице 2x2:
        // Сравниваем ширину верхней короны/башни/креста (wTop) со средней частью (wMid)
        val wTop  = avgWidthRel(0.08f, 0.28f)
        val wMid  = avgWidthRel(0.34f, 0.58f)
        val wPeak = maxWidthRel(0.10f, 0.58f)

        val pieceType = if (wTop >= wMid * 0.91f) {
            // Верх широкий (Ферзь Q или Ладья R): у Ферзя широкая трехзубая корона, у Ладьи более узкая башня
            if (wPeak >= 0.72f || wTop >= 0.64f) 'q' else 'r'
        } else {
            // Верх узкий — крест или кончик митры, середина широкая (Король K или Слон B):
            // У Короля массивные боковые доли короны (wPeak >= 0.72f), у Слона гладкая митра поуже
            if (wPeak >= 0.72f || wMid >= 0.65f) 'k' else 'b'
        }

        return if (isWhite) pieceType.uppercaseChar() else pieceType
    }

    /**
     * Шахматная нормализация позиции:
     * исправляет невозможные количества фигур и уточняет стартовые поля первой/восьмой горизонталей.
     */
    private fun normalizeBoardState(grid: Array<CharArray>) {
        // 1. Пешки не могут находиться на 0-й (8-я горизонталь) и 7-й (1-я горизонталь) строках
        for (c in 0..7) {
            if (grid[0][c] == 'P') grid[0][c] = 'Q'
            if (grid[7][c] == 'P') grid[7][c] = 'B'
            if (grid[0][c] == 'p') grid[0][c] = 'b'
            if (grid[7][c] == 'p') grid[7][c] = 'q'
        }

        // 2. Если на 2-й горизонтали (row 6) или 7-й горизонтали (row 1) стоит ряд своих фигур перед начальным рядом,
        // то фигуры на (row 6 / row 1) с высокой вероятностью являются пешками, если их классифицировали как слонов
        val whiteBackRankCount = (0..7).count { grid[7][it].isUpperCase() }
        if (whiteBackRankCount >= 5) {
            for (c in 0..7) {
                if (grid[6][c] in charArrayOf('B', 'R', 'K')) grid[6][c] = 'P'
            }
            // Уточняем домашние поля белых, если дебютная расстановка сохранена
            if (grid[7][0].isUpperCase()) grid[7][0] = 'R'
            if (grid[7][7].isUpperCase()) grid[7][7] = 'R'
            if (grid[7][1].isUpperCase() && grid[6][1] == 'P') grid[7][1] = 'N'
            if (grid[7][6].isUpperCase() && grid[6][6] == 'P') grid[7][6] = 'N'
            if (grid[7][2].isUpperCase() && grid[6][2] == 'P') grid[7][2] = 'B'
            if (grid[7][5].isUpperCase() && grid[6][5] == 'P') grid[7][5] = 'B'
            if (grid[7][3].isUpperCase() && grid[7][4].isUpperCase()) {
                grid[7][3] = 'Q'
                grid[7][4] = 'K'
            }
        }

        val blackBackRankCount = (0..7).count { grid[0][it].isLowerCase() }
        if (blackBackRankCount >= 5) {
            for (c in 0..7) {
                if (grid[1][c] in charArrayOf('b', 'r', 'k')) grid[1][c] = 'p'
            }
            // Уточняем домашние поля черных в дебюте
            if (grid[0][0].isLowerCase()) grid[0][0] = 'r'
            if (grid[0][7].isLowerCase()) grid[0][7] = 'r'
            if (grid[0][1].isLowerCase() && grid[1][1] == 'p') grid[0][1] = 'n'
            if (grid[0][6].isLowerCase() && grid[1][6] == 'p') grid[0][6] = 'n'
            if (grid[0][2].isLowerCase() && grid[1][2] == 'p') grid[0][2] = 'b'
            if (grid[0][5].isLowerCase() && grid[1][5] == 'p') grid[0][5] = 'b'
            if (grid[0][3].isLowerCase() && grid[0][4].isLowerCase()) {
                grid[0][3] = 'q'
                grid[0][4] = 'k'
            }
        }

        // 3. Гарантируем ровно одного белого Короля 'K' и одного черного Короля 'k'
        enforceSingleKing(grid, isWhite = true)
        enforceSingleKing(grid, isWhite = false)
    }

    private fun enforceSingleKing(grid: Array<CharArray>, isWhite: Boolean) {
        val kingChar = if (isWhite) 'K' else 'k'
        val queenChar = if (isWhite) 'Q' else 'q'
        val homeRow = if (isWhite) 7 else 0

        val kings = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) for (c in 0..7) if (grid[r][c] == kingChar) kings.add(r to c)

        if (kings.isEmpty()) {
            val prioritySquares = listOf(homeRow to 4, homeRow to 6, homeRow to 2, homeRow to 3)
            val candidate = prioritySquares.firstOrNull { (r, c) ->
                val ch = grid[r][c]
                ch != '.' && ch.isUpperCase() == isWhite && ch.uppercaseChar() != 'P'
            }
            if (candidate != null) {
                grid[candidate.first][candidate.second] = kingChar
            } else {
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
        } else if (kings.size > 1) {
            val best = kings.minByOrNull { (r, c) -> abs(r - homeRow) * 2 + abs(c - 4) }!!
            for (pos in kings) {
                if (pos != best) grid[pos.first][pos.second] = queenChar
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
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
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
        if (total < 2 || total > 32) return false to "Калибровка ($total фиг.)"
        return true to "Позиция ОК ($total фиг.)"
    }

    private fun detectContinueButton(bmp: Bitmap): Boolean {
        val x = (bmp.width * 0.60f).toInt()
        val y = (bmp.height * 0.91f).toInt()
        if (x !in 0 until bmp.width || y !in 0 until bmp.height) return false
        val px = bmp.getPixel(x, y)
        val r = Color.red(px)
        val g = Color.green(px)
        val b = Color.blue(px)
        return g > 160 && g > r + 50 && g > b + 70
    }
}
