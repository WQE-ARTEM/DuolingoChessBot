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
                grid[r][c] = classifyCellTwoPass(bitmap, cellX, cellY, cw, ch)
            }
        }

        // Приводим матрицу к ориентации FEN (8-я горизонталь сверху)
        val canonicalGrid = Array(8) { CharArray(8) { '.' } }
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                canonicalGrid[r][c] = if (whiteAtBottom) grid[r][c] else grid[7 - r][7 - c]
            }
        }

        // Нормализация королей и пешек на крайних горизонталях
        normalizeBoardState(canonicalGrid)

        // Синхронизируем отображаемые метки с нормализованной доской
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
     * Двухпроходный классификатор клеток Duolingo Chess:
     * Проход 1: Отделение пустых клеток (L <= 53) от белых (L >= 175) и черных фигур (L 58..160 + контур L >= 95).
     * Проход 2: Анализ точной геометрии силуэта внутри собственной рамки фигуры (minY..maxY).
     */
    private fun classifyCellTwoPass(bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int): Char {
        if (x0 < 0 || y0 < 0 || x0 + w > bmp.width || y0 + h > bmp.height || w < 20 || h < 20) return '.'

        val marginX = (w * 0.15f).toInt()
        val marginYTop = (h * 0.07f).toInt()
        val marginYBot = (h * 0.10f).toInt()

        val scanW = w - 2 * marginX
        val scanH = h - marginYTop - marginYBot
        if (scanW <= 4 || scanH <= 4) return '.'

        var whitePixels = 0
        var blackBodyPixels = 0
        var blackOutlinePixels = 0
        var totalSamples = 0
        var minLum = 255
        var maxLum = 0

        // Проход 1: определяем, пустая ли клетка, и какого цвета фигура
        for (dy in 0 until scanH step 2) {
            for (dx in 0 until scanW step 2) {
                val px = bmp.getPixel(x0 + marginX + dx, y0 + marginYTop + dy)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
                totalSamples++

                // Игнорируем ярко-зеленую или желтую подсветку пустых клеток (где G >> B)
                val isHighlightTint = (g > b + 28 && g > r + 20)
                if (isHighlightTint) continue

                if (lum >= 172 && b >= r - 15) {
                    whitePixels++
                } else if (lum in 58..162 && b >= r - 6 && g >= r - 6) {
                    blackBodyPixels++
                    if (lum >= 94) blackOutlinePixels++
                }
            }
        }

        if (totalSamples == 0) return '.'

        // Пустые клетки Duolingo (#131F24 и #202F36) имеют яркость L = 28..45 и низкий внутренний контраст
        val lumContrast = maxLum - minLum
        if (lumContrast < 32 || maxLum < 78) return '.'

        val whiteRatio = whitePixels.toFloat() / totalSamples
        val blackRatio = blackBodyPixels.toFloat() / totalSamples
        val outlineRatio = blackOutlinePixels.toFloat() / totalSamples

        val isWhitePiece = when {
            whiteRatio >= 0.11f -> true
            blackRatio >= 0.13f && outlineRatio >= 0.022f -> false
            else -> return '.' // Клетка пустая!
        }

        // Проход 2: строим маску фигуры и находим её точный Bounding Box
        val mask = Array(scanH) { BooleanArray(scanW) }
        var minX = scanW; var maxX = 0
        var minY = scanH; var maxY = 0
        var fgCount = 0

        for (dy in 0 until scanH) {
            for (dx in 0 until scanW) {
                val px = bmp.getPixel(x0 + marginX + dx, y0 + marginYTop + dy)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                val isFg = if (isWhitePiece) {
                    lum >= 165 && b >= r - 18
                } else {
                    lum in 56..165 && b >= r - 8
                }

                if (isFg) {
                    mask[dy][dx] = true
                    fgCount++
                    if (dx < minX) minX = dx
                    if (dx > maxX) maxX = dx
                    if (dy < minY) minY = dy
                    if (dy > maxY) maxY = dy
                }
            }
        }

        val pieceH = maxY - minY + 1
        val pieceW = maxX - minX + 1
        if (pieceH < scanH * 0.28f || pieceW < scanW * 0.25f) return '.'

        val heightRatio = pieceH.toFloat() / scanH
        val fillRatio = fgCount.toFloat() / (scanW * scanH)
        val topStartRatio = minY.toFloat() / scanH

        // 1. ПЕШКА (P): самая низкая и компактная фигура, начинается значительно ниже остальных
        if (heightRatio < 0.68f || topStartRatio > 0.21f || fillRatio < 0.29f) {
            return if (isWhitePiece) 'P' else 'p'
        }

        // Измеряем ширину силуэта на разных уровнях высоты самой фигуры (0.0 = верхушка, 1.0 = основание)
        fun widthAtBand(yStartFrac: Float, yEndFrac: Float): Float {
            val y1 = (minY + pieceH * yStartFrac).toInt().coerceIn(0, scanH - 1)
            val y2 = (minY + pieceH * yEndFrac).toInt().coerceIn(y1 + 1, scanH)
            var sumW = 0f
            var rows = 0
            for (y in y1 until y2) {
                var rowMin = scanW; var rowMax = -1
                for (x in minX..maxX) {
                    if (mask[y][x]) {
                        if (x < rowMin) rowMin = x
                        if (x > rowMax) rowMax = x
                    }
                }
                if (rowMax >= rowMin) {
                    sumW += (rowMax - rowMin + 1)
                    rows++
                }
            }
            return if (rows > 0) sumW / (rows * scanW) else 0f
        }

        // Считаем центр тяжести по X и асимметрию в верхней половине фигуры
        var sumXUpper = 0f; var countUpper = 0
        var sumXMid = 0f; var countMid = 0
        var topCenterHollow = 0; var topCenterTotal = 0

        val yCrownStart = (minY + pieceH * 0.12f).toInt()
        val yCrownEnd = (minY + pieceH * 0.34f).toInt()
        val xCenterLeft = (minX + pieceW * 0.36f).toInt()
        val xCenterRight = (minX + pieceW * 0.64f).toInt()

        for (y in minY..maxY) {
            val relY = (y - minY).toFloat() / pieceH
            for (x in minX..maxX) {
                if (mask[y][x]) {
                    if (relY in 0.15f..0.45f) {
                        sumXUpper += x; countUpper++
                    } else if (relY in 0.46f..0.72f) {
                        sumXMid += x; countMid++
                    }
                }
                if (y in yCrownStart..yCrownEnd && x in xCenterLeft..xCenterRight) {
                    topCenterTotal++
                    if (!mask[y][x]) topCenterHollow++
                }
            }
        }

        val cmXUpper = if (countUpper > 0) sumXUpper / countUpper else scanW / 2f
        val cmXMid = if (countMid > 0) sumXMid / countMid else scanW / 2f
        val knightShift = (cmXMid - cmXUpper) / scanW // У коня морда слева (cmXUpper левее), шея справа (cmXMid правее)
        val hollowRatio = if (topCenterTotal > 0) topCenterHollow.toFloat() / topCenterTotal else 0f

        val wVeryTop = widthAtBand(0.04f, 0.16f)
        val wCrown   = widthAtBand(0.14f, 0.32f)
        val wMid     = widthAtBand(0.33f, 0.55f)
        val wBase    = widthAtBand(0.72f, 0.90f)

        // 2. КОНЬ (N): выраженный сдвиг центра тяжести (морда выступает влево вверху, вырез под челюстью слева)
        if (knightShift > 0.042f) {
            return if (isWhitePiece) 'N' else 'n'
        }

        // 3. ЛАДЬЯ (R): широкая плоская башня сверху с темным овальным углублением в центре башни
        if (wVeryTop > 0.50f && hollowRatio > 0.18f && wCrown >= wMid * 0.92f) {
            return if (isWhitePiece) 'R' else 'r'
        }

        // 4. ФЕРЗЬ (Q): очень широкая трехзубая корона в верхней трети (шире основания!)
        if (wCrown > 0.62f && wCrown >= wBase * 0.96f) {
            return if (isWhitePiece) 'Q' else 'q'
        }

        // 5. КОРОЛЬ (K) vs СЛОН (B):
        // У Короля узкий крест на самой верхушке (wVeryTop < 0.42), который переходит в очень широкую корону (wMid > 0.62)
        val pieceType = if (wMid >= 0.60f && wMid >= wBase * 0.94f) {
            'k'
        } else {
            'b'
        }

        return if (isWhitePiece) pieceType.uppercaseChar() else pieceType
    }

    /**
     * Гарантирует валидность шахматной позиции (ровно 1 белый и 1 черный король, отсутствие пешек на 1/8 горизонталях).
     */
    private fun normalizeBoardState(grid: Array<CharArray>) {
        // 1. Пешки на 1-й и 8-й горизонталях невозможны
        for (c in 0..7) {
            if (grid[0][c] == 'P') grid[0][c] = 'Q'
            if (grid[7][c] == 'P') grid[7][c] = 'N'
            if (grid[0][c] == 'p') grid[0][c] = 'n'
            if (grid[7][c] == 'p') grid[7][c] = 'q'
        }

        // 2. Классическая расстановка первой/восьмой горизонтали, если фигуры стоят на стартовых местах
        if (grid[7][4] in charArrayOf('Q', 'B', 'R', 'N') && grid[7][0] != '.' && grid[7][7] != '.') {
            grid[7][4] = 'K'
        }
        if (grid[0][4] in charArrayOf('q', 'b', 'r', 'n') && grid[0][0] != '.' && grid[0][7] != '.') {
            grid[0][4] = 'k'
        }

        // 3. Гарантируем ровно одного белого Короля 'K'
        val whiteKings = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) for (c in 0..7) if (grid[r][c] == 'K') whiteKings.add(r to c)

        if (whiteKings.isEmpty()) {
            // Ищем наиболее вероятного кандидата на роль белого короля (начиная с e1)
            val candidates = listOf(7 to 4, 7 to 6, 7 to 2, 7 to 3, 6 to 4)
            val found = candidates.firstOrNull { (r, c) -> grid[r][c].isUpperCase() && grid[r][c] != 'P' }
            if (found != null) {
                grid[found.first][found.second] = 'K'
            } else {
                loop@ for (r in 7 downTo 0) {
                    for (c in 0..7) {
                        if (grid[r][c] in charArrayOf('Q', 'B', 'R', 'N', 'P')) {
                            grid[r][c] = 'K'
                            break@loop
                        }
                    }
                }
            }
        } else if (whiteKings.size > 1) {
            val bestK = whiteKings.minByOrNull { (r, c) -> abs(r - 7) + abs(c - 4) }!!
            for (pos in whiteKings) {
                if (pos != bestK) grid[pos.first][pos.second] = 'Q'
            }
        }

        // 4. Гарантируем ровно одного черного Короля 'k'
        val blackKings = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) for (c in 0..7) if (grid[r][c] == 'k') blackKings.add(r to c)

        if (blackKings.isEmpty()) {
            val candidates = listOf(0 to 4, 0 to 6, 0 to 2, 0 to 3, 1 to 4)
            val found = candidates.firstOrNull { (r, c) -> grid[r][c].isLowerCase() && grid[r][c] != 'p' }
            if (found != null) {
                grid[found.first][found.second] = 'k'
            } else {
                loop@ for (r in 0..7) {
                    for (c in 0..7) {
                        if (grid[r][c] in charArrayOf('q', 'b', 'r', 'n', 'p')) {
                            grid[r][c] = 'k'
                            break@loop
                        }
                    }
                }
            }
        } else if (blackKings.size > 1) {
            val bestK = blackKings.minByOrNull { (r, c) -> abs(r - 0) + abs(c - 4) }!!
            for (pos in blackKings) {
                if (pos != bestK) grid[pos.first][pos.second] = 'q'
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
        if (total < 2 || total > 34) return false to "Калибровка ($total фиг.)"
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
