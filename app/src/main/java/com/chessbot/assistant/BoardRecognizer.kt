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
                grid[r][c] = classifyDuolingoPiece(bitmap, cellX, cellY, cw, ch)
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

        // Синхронизируем экранную сетку с нормализованной доской
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
     * Классификатор фигур Duolingo Chess по точным контурам и внутренним вырезам (1000073060.jpg).
     */
    private fun classifyDuolingoPiece(bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int): Char {
        if (x0 < 0 || y0 < 0 || x0 + w > bmp.width || y0 + h > bmp.height || w < 24 || h < 24) return '.'

        // 1. Определяем цвет фона самой клетки (по верхним боковым полям, где никогда не бывает фигур)
        var bgR = 0; var bgG = 0; var bgB = 0; var bgCount = 0
        for (dy in (h * 0.14f).toInt() until (h * 0.48f).toInt() step 3) {
            for (dx in listOf((w * 0.05f).toInt(), (w * 0.94f).toInt())) {
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

        // 2. Сканируем силуэт с 4% до 76% высоты клетки (исключая попадание верхушек нижнего ряда)
        val xStart = (w * 0.12f).toInt()
        val xEnd = (w * 0.88f).toInt()
        val yStart = (h * 0.04f).toInt()
        val yEnd = (h * 0.76f).toInt()
        val scanW = xEnd - xStart
        val scanH = yEnd - yStart
        if (scanW <= 8 || scanH <= 8) return '.'

        val mask = Array(scanH) { BooleanArray(scanW) }
        var fgPixels = 0
        var whiteVote = 0

        for (dy in 0 until scanH) {
            for (dx in 0 until scanW) {
                val px = bmp.getPixel(x0 + xStart + dx, y0 + yStart + dy)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                val diffBg = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
                if (diffBg >= 36 && lum > bgLum + 15) {
                    mask[dy][dx] = true
                    fgPixels++
                    if (lum >= 130) whiteVote++
                }
            }
        }

        val fillRatio = fgPixels.toFloat() / (scanW * scanH)
        if (fillRatio < 0.11f) return '.'

        val isWhite = whiteVote > fgPixels * 0.40f

        // 3. Вычисляем ширину и центр каждой горизонтальной строки силуэта
        val wProfile = FloatArray(scanH)
        val cxProfile = FloatArray(scanH)
        val leftProfile = FloatArray(scanH)
        var topY = -1

        for (dy in 0 until scanH) {
            var rMin = scanW
            var rMax = -1
            var cnt = 0
            for (dx in 0 until scanW) {
                if (mask[dy][dx]) {
                    if (dx < rMin) rMin = dx
                    if (dx > rMax) rMax = dx
                    cnt++
                }
            }
            if (cnt >= 2 && rMax > rMin) {
                val rw = (rMax - rMin + 1).toFloat() / scanW
                wProfile[dy] = rw
                cxProfile[dy] = ((rMin + rMax) * 0.5f) / scanW
                leftProfile[dy] = rMin.toFloat() / scanW
                if (topY == -1 && rw >= 0.14f) topY = dy
            }
        }

        if (topY == -1) return '.'
        val topStartFrac = topY.toFloat() / scanH

        // Измеряем ширину в зоне головки/короны (от 12% до 52% высоты скана)
        var maxHeadW = 0f
        var sumHeadW = 0f
        var headRows = 0
        for (dy in (scanH * 0.12f).toInt() until (scanH * 0.52f).toInt()) {
            if (wProfile[dy] > 0.05f) {
                if (wProfile[dy] > maxHeadW) maxHeadW = wProfile[dy]
                sumHeadW += wProfile[dy]
                headRows++
            }
        }
        val avgHeadW = if (headRows > 0) sumHeadW / headRows else 0f

        // ПРАВИЛО 1: ПЕШКА (P / p)
        // У пешки компактная круглая головка и узкая шейка: её средняя ширина в верхней половине < 0.44
        if (maxHeadW < 0.54f || avgHeadW < 0.43f || topStartFrac >= 0.19f) {
            return if (isWhite) 'P' else 'p'
        }

        val remH = (scanH - topY).coerceAtLeast(10)
        fun avgW(f1: Float, f2: Float): Float {
            val y1 = (topY + remH * f1).toInt().coerceIn(0, scanH - 1)
            val y2 = (topY + remH * f2).toInt().coerceIn(y1 + 1, scanH)
            var s = 0f; var c = 0
            for (y in y1 until y2) if (wProfile[y] > 0.05f) { s += wProfile[y]; c++ }
            return if (c > 0) s / c else 0f
        }
        fun maxW(f1: Float, f2: Float): Float {
            val y1 = (topY + remH * f1).toInt().coerceIn(0, scanH - 1)
            val y2 = (topY + remH * f2).toInt().coerceIn(y1 + 1, scanH)
            var m = 0f
            for (y in y1 until y2) if (wProfile[y] > m) m = wProfile[y]
            return m
        }
        fun avgCx(f1: Float, f2: Float): Float {
            val y1 = (topY + remH * f1).toInt().coerceIn(0, scanH - 1)
            val y2 = (topY + remH * f2).toInt().coerceIn(y1 + 1, scanH)
            var s = 0f; var c = 0
            for (y in y1 until y2) if (wProfile[y] > 0.10f) { s += cxProfile[y]; c++ }
            return if (c > 0) s / c else 0.5f
        }
        fun avgLeft(f1: Float, f2: Float): Float {
            val y1 = (topY + remH * f1).toInt().coerceIn(0, scanH - 1)
            val y2 = (topY + remH * f2).toInt().coerceIn(y1 + 1, scanH)
            var s = 0f; var c = 0
            for (y in y1 until y2) if (wProfile[y] > 0.10f) { s += leftProfile[y]; c++ }
            return if (c > 0) s / c else 0.2f
        }

        // ПРАВИЛО 2: КОНЬ (N / n) — морда выступает влево, а под ней справа остается шея
        val snoutCx = avgCx(0.18f, 0.42f)
        val neckCx  = avgCx(0.48f, 0.70f)
        val snoutLeft = avgLeft(0.18f, 0.42f)
        val jawLeft   = avgLeft(0.48f, 0.70f)
        if ((neckCx - snoutCx) > 0.042f && (jawLeft - snoutLeft) > 0.060f) {
            return if (isWhite) 'N' else 'n'
        }

        // ПРАВИЛО 3: Различаем R, Q, K, B
        val wTop  = avgW(0.06f, 0.25f)
        val wMid  = avgW(0.32f, 0.56f)
        val wPeak = maxW(0.10f, 0.58f)

        val pieceType = if (wTop >= wMid * 0.90f) {
            if (wPeak >= 0.71f || wTop >= 0.63f) 'q' else 'r'
        } else {
            if (wPeak >= 0.71f || wMid >= 0.64f) 'k' else 'b'
        }

        return if (isWhite) pieceType.uppercaseChar() else pieceType
    }

    private fun normalizeBoardState(grid: Array<CharArray>) {
        for (c in 0..7) {
            if (grid[0][c] == 'P') grid[0][c] = 'Q'
            if (grid[7][c] == 'P') grid[7][c] = 'B'
            if (grid[0][c] == 'p') grid[0][c] = 'b'
            if (grid[7][c] == 'p') grid[7][c] = 'q'
        }

        // Если большая часть первого/восьмого ряда стоит на начальных местах, фиксируем домашние фигуры и пешки перед ними
        val whiteBackCount = (0..7).count { grid[7][it].isUpperCase() }
        if (whiteBackCount >= 5) {
            for (c in 0..7) {
                if (grid[6][c].isUpperCase() && grid[7][c].isUpperCase()) grid[6][c] = 'P'
            }
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

        val blackBackCount = (0..7).count { grid[0][it].isLowerCase() }
        if (blackBackCount >= 5) {
            for (c in 0..7) {
                if (grid[1][c].isLowerCase() && grid[0][c].isLowerCase()) grid[1][c] = 'p'
            }
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
