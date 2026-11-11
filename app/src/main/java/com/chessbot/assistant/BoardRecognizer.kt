package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    data class ScanResult(
        val board: Array<CharArray>,
        val fen: String,
        val isValid: Boolean,
        val statusNote: String,
        val continueButtonActive: Boolean = false,
        val detectedWhiteAtBottom: Boolean = true
    )

    fun isFrameStable(
        prev: Bitmap?,
        curr: Bitmap,
        topPx: Int,
        sizePx: Int
    ): Boolean {
        if (prev == null || prev.width != curr.width || prev.height != curr.height) return false
        val safeTop = topPx.coerceIn(0, max(0, curr.height - 10))
        val safeSize = min(sizePx, curr.height - safeTop)
        if (safeSize < 80) return true

        var diffCount = 0
        var totalSamples = 0
        val step = max(12, safeSize / 24)

        var y = safeTop + step
        while (y < safeTop + safeSize - step) {
            var x = step
            while (x < curr.width - step) {
                val p1 = prev.getPixel(x, y)
                val p2 = curr.getPixel(x, y)
                val dr = abs(Color.red(p1) - Color.red(p2))
                val dg = abs(Color.green(p1) - Color.green(p2))
                val db = abs(Color.blue(p1) - Color.blue(p2))
                if (dr + dg + db > 45) diffCount++
                totalSamples++
                x += step
            }
            y += step
        }
        return totalSamples == 0 || (diffCount.toFloat() / totalSamples) < 0.045f
    }

    fun recognizeBoard(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int,
        whiteAtBottom: Boolean = true,
        lockedFenState: String = ""
    ): ScanResult {
        val board = Array(8) { CharArray(8) { '.' } }
        val continueActive = detectContinueButton(bitmap)

        val left = boardLeftPx.coerceIn(0, max(0, bitmap.width - 64))
        val top = boardTopPx.coerceIn(0, max(0, bitmap.height - 64))
        val size = min(boardSizePx, min(bitmap.width - left, bitmap.height - top))

        if (size < 120) {
            return ScanResult(board, "", false, "Сетка слишком мала", continueActive, whiteAtBottom)
        }

        val cellSize = size / 8f
        val kingScore = Array(8) { IntArray(8) { -1000 } }

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val cellX = (left + c * cellSize).toInt()
                val cellY = (top + r * cellSize).toInt()
                val cellW = cellSize.toInt()
                val cellH = cellSize.toInt()

                val cellInfo = classifyCell(bitmap, cellX, cellY, cellW, cellH, r, c)
                board[r][c] = cellInfo.first
                kingScore[r][c] = cellInfo.second
            }
        }

        // Оставляем ровно по одному королю на каждую сторону (лучшего кандидата)
        enforceSingleKing(board, kingScore, isWhite = true)
        enforceSingleKing(board, kingScore, isWhite = false)

        // Если это стартовая расстановка (все 32 клетки на рядах 0,1,6,7 заняты, а центр пуст) — фиксируем точную начальную расстановку
        val occupiedStart = (0 until 8).count { board[0][it] != '.' } +
                (0 until 8).count { board[1][it] != '.' } +
                (0 until 8).count { board[6][it] != '.' } +
                (0 until 8).count { board[7][it] != '.' }
        val emptyMiddle = (2..5).sumOf { r -> (0 until 8).count { c -> board[r][c] == '.' } }

        if (occupiedStart >= 30 && emptyMiddle >= 30) {
            val backRank = charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')
            val bottomWhite = board[7].count { it.isUpperCase() } >= board[0].count { it.isUpperCase() }
            for (c in 0 until 8) {
                val p = if (bottomWhite) backRank[c] else if (c == 3) 'k' else if (c == 4) 'q' else backRank[c]
                board[0][c] = if (bottomWhite) p else p.uppercaseChar()
                board[1][c] = if (bottomWhite) 'p' else 'P'
                board[6][c] = if (bottomWhite) 'P' else 'p'
                board[7][c] = if (bottomWhite) p.uppercaseChar() else p
            }
        }

        var whiteKing = false
        var blackKing = false
        var totalPieces = 0
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val ch = board[r][c]
                if (ch != '.') totalPieces++
                if (ch == 'K') whiteKing = true
                if (ch == 'k') blackKing = true
            }
        }

        // Если король был перекрыт зелёной стрелкой, восстанавливаем его на наиболее вероятной клетке
        if (!whiteKing && totalPieces >= 3) {
            val bestPos = findFallbackKingSquare(board, kingScore, isWhite = true)
            if (bestPos != null) {
                board[bestPos.first][bestPos.second] = 'K'
                whiteKing = true
            }
        }
        if (!blackKing && totalPieces >= 3) {
            val bestPos = findFallbackKingSquare(board, kingScore, isWhite = false)
            if (bestPos != null) {
                board[bestPos.first][bestPos.second] = 'k'
                blackKing = true
            }
        }

        val isValid = whiteKing && blackKing && totalPieces >= 2
        val fen = buildFen(board, whiteAtBottom)
        val status = if (isValid) "OK ($totalPieces фиг.)" else "Совместите сетку с доской (фигур: $totalPieces)"

        return ScanResult(
            board = board,
            fen = fen,
            isValid = isValid,
            statusNote = status,
            continueButtonActive = continueActive,
            detectedWhiteAtBottom = whiteAtBottom
        )
    }

    private fun classifyCell(
        bmp: Bitmap,
        cellX: Int,
        cellY: Int,
        cellW: Int,
        cellH: Int,
        row: Int,
        col: Int
    ): Pair<Char, Int> {
        val N = 32
        val fg = Array(N) { BooleanArray(N) }
        val darkHole = Array(N) { BooleanArray(N) }

        var whitePixels = 0
        var blackPixels = 0
        var validSamples = 0

        // Оцениваем яркость фона конкретной клетки по краям (внизу/справа в углах)
        var bgLumSum = 0
        var bgCount = 0
        val cornerCoords = arrayOf(
            3 to 28, 4 to 28, 27 to 28, 28 to 28,
            27 to 3, 28 to 3, 28 to 4, 3 to 20, 28 to 20
        )
        for ((gx, gy) in cornerCoords) {
            val px = (cellX + (gx + 0.5f) * cellW / N).toInt().coerceIn(0, bmp.width - 1)
            val py = (cellY + (gy + 0.5f) * cellH / N).toInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(px, py)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (!(g > r + 35 && g > b + 20)) {
                bgLumSum += (r * 299 + g * 587 + b * 114) / 1000
                bgCount++
            }
        }
        val isLightSquare = (row + col) % 2 == 0
        val bgLum = if (bgCount > 0) bgLumSum / bgCount else if (isLightSquare) 56 else 35

        val lumGrid = Array(N) { IntArray(N) { bgLum } }
        val greenOverlay = Array(N) { BooleanArray(N) }

        for (gy in 2 until N - 2) {
            for (gx in 3 until N - 3) {
                // Пропускаем левый верхний угол клетки, где оверлей рисует мелкий текст (wQ, bP и т.д.)
                if (gx < 10 && gy < 7) continue

                val px = (cellX + (gx + 0.5f) * cellW / N).toInt().coerceIn(0, bmp.width - 1)
                val py = (cellY + (gy + 0.5f) * cellH / N).toInt().coerceIn(0, bmp.height - 1)
                val color = bmp.getPixel(px, py)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                // Фильтруем ярко-зелёную стрелку и рамку оверлея
                if (g > r + 38 && g > b + 22 && lum < 200) {
                    greenOverlay[gy][gx] = true
                    continue
                }

                lumGrid[gy][gx] = lum
                validSamples++

                // Белые фигуры в Duolingo: ярко-белые/голубоватые (lum >= 162)
                if (lum >= 162 && b >= r - 20) {
                    whitePixels++
                }
                // Чёрные фигуры в Duolingo: серо-синие (#597584, lum 75..158), заметно светлее тёмно-бирюзовой клетки
                else if (lum in 74..160 && b >= r + 4 && g >= r && lum >= bgLum + 18) {
                    blackPixels++
                }
            }
        }

        if (validSamples < 100) return '.' to -1000

        // Порог с запасом под пешки (пешка занимает ~13-25% клетки, крупные фигуры ~30-55%)
        val isWhitePiece: Boolean
        if (whitePixels >= 48 && whitePixels > blackPixels) {
            isWhitePiece = true
        } else if (blackPixels >= 42) {
            isWhitePiece = false
        } else {
            return '.' to -1000
        }

        var totalFg = 0
        var topY = 31
        var bottomY = 0
        val rowWidth = IntArray(N)
        val rowLeft = IntArray(N) { 31 }
        val rowRight = IntArray(N) { 0 }

        for (gy in 2 until N - 2) {
            for (gx in 3 until N - 3) {
                if (gx < 10 && gy < 7) continue
                if (greenOverlay[gy][gx]) continue
                val lum = lumGrid[gy][gx]
                val isFg = if (isWhitePiece) {
                    lum >= 155
                } else {
                    lum in 72..161 && lum >= bgLum + 16
                }
                if (isFg) {
                    fg[gy][gx] = true
                    totalFg++
                    if (gy < topY) topY = gy
                    if (gy > bottomY) bottomY = gy
                    if (gx < rowLeft[gy]) rowLeft[gy] = gx
                    if (gx > rowRight[gy]) rowRight[gy] = gx
                } else if (lum <= 48) {
                    darkHole[gy][gx] = true
                }
            }
            if (rowRight[gy] >= rowLeft[gy]) {
                rowWidth[gy] = rowRight[gy] - rowLeft[gy] + 1
            }
        }

        if (totalFg < 40 || bottomY <= topY + 4) return '.' to -1000

        // Считаем ключевые морфологические признаки фигуры
        var upperBandFg = 0 // gy in 2..7 (верхняя шапка фигуры)
        for (gy in 2..7) for (gx in 4..27) if (fg[gy][gx]) upperBandFg++

        val crownWidth = (6..10).maxOf { rowWidth[it] }          // ширина верхней короны
        val avgCrownWidth = (6..10).map { rowWidth[it] }.average()
        val avgMidWidth = (13..18).map { rowWidth[it] }.average() // ширина средней части
        val avgWaistWidth = (18..22).map { rowWidth[it] }.average()

        // 1. ПЕШКА (Pawn): не бывает на 0-й и 7-й горизонтали; заметно ниже и уже остальных фигур
        if (row in 1..6) {
            val isShort = topY >= 6 || upperBandFg <= 18
            val isNarrowTop = crownWidth <= 13
            val isSmallArea = totalFg <= 195
            if ((isShort && isNarrowTop) || (isSmallArea && crownWidth <= 14 && upperBandFg <= 24)) {
                return (if (isWhitePiece) 'P' else 'p') to -500
            }
        }

        // 2. КОНЬ (Knight): единственная асимметричная фигура (морда слева, шея справа, под мордой слева пустота)
        var neckLeft = 0
        var neckRight = 0
        for (gy in 15..22) {
            for (gx in 5..14) if (fg[gy][gx]) neckLeft++
            for (gx in 17..26) if (fg[gy][gx]) neckRight++
        }
        var snoutLeft = 0
        var snoutRight = 0
        for (gy in 9..14) {
            for (gx in 5..12) if (fg[gy][gx]) snoutLeft++
            for (gx in 19..26) if (fg[gy][gx]) snoutRight++
        }
        val knightScore = (neckRight - neckLeft) * 2 + (snoutLeft - snoutRight)
        if (knightScore >= 24 && neckRight >= neckLeft + 10) {
            return (if (isWhitePiece) 'N' else 'n') to -300
        }

        // 3. Внутренние вырезы в верхней части (у ладьи овальное отверстие, у короля крест, у ферзя подушка короны)
        var topCenterTip = 0 // gy in 2..5, gx in 13..18 (крест короля или шарик слона)
        for (gy in 2..5) for (gx in 13..18) if (fg[gy][gx]) topCenterTip++

        var innerCutout = 0 // тёмные пиксели внутри верхней части фигуры (gy in 5..11, gx in 11..20)
        for (gy in 5..11) {
            for (gx in 11..20) {
                if (!fg[gy][gx] && rowLeft[gy] < gx - 1 && rowRight[gy] > gx + 1) {
                    innerCutout++
                }
            }
        }

        var crossCutout = 0 // вырез креста короля (gy in 4..8, gx in 13..18)
        for (gy in 4..8) {
            for (gx in 13..18) {
                if (!fg[gy][gx] && rowLeft[gy] <= 12 && rowRight[gy] >= 19) {
                    crossCutout++
                }
            }
        }

        // Оценка вероятности КОРОЛЯ (K)
        var kScore = 0
        if (topCenterTip >= 6) kScore += 30
        if (crossCutout >= 4) kScore += 35
        if (avgMidWidth >= 17.5) kScore += 25
        if (crownWidth in 15..21) kScore += 15
        if ((row == 0 || row == 7) && col == 4) kScore += 20 // бонус стартового поля e1/e8

        // 4. СЛОН (Bishop): узкий купол сверху, расширяющийся к середине + диагональная прорезь слева
        var bishopSlit = 0
        for (gy in 9..14) {
            for (gx in 9..14) {
                if (!fg[gy][gx] && fg[gy][min(28, gx + 4)]) bishopSlit++
            }
        }
        if (avgCrownWidth <= avgMidWidth * 0.78 && crownWidth <= 15 && avgMidWidth >= 14.0) {
            return (if (isWhitePiece) 'B' else 'b') to kScore
        }

        // 5. ЛАДЬЯ (Rook): плоский верх (мало пикселей на gy 2..4), прямые вертикальные стенки и овальное отверстие сверху
        val flatTop = topCenterTip <= 5
        val straightWalls = abs(avgCrownWidth - avgMidWidth) <= 2.6 && crownWidth in 14..19
        if (flatTop && straightWalls && innerCutout >= 6) {
            return (if (isWhitePiece) 'R' else 'r') to kScore
        }

        // 6. ФЕРЗЬ (Queen) vs КОРОЛЬ (King) vs ЛАДЬЯ/СЛОН:
        // У ферзя самая широкая корона сверху (шире талии: avgCrownWidth >= avgWaistWidth)
        if (crownWidth >= 19 && avgCrownWidth >= avgWaistWidth - 0.5 && crossCutout <= 5) {
            return (if (isWhitePiece) 'Q' else 'q') to kScore
        }

        if (kScore >= 65) {
            return (if (isWhitePiece) 'K' else 'k') to kScore
        }

        if (flatTop && crownWidth <= 18) {
            return (if (isWhitePiece) 'R' else 'r') to kScore
        }
        if (avgCrownWidth < avgMidWidth - 1.5 || bishopSlit >= 8) {
            return (if (isWhitePiece) 'B' else 'b') to kScore
        }

        return (if (isWhitePiece) 'Q' else 'q') to kScore
    }

    private fun enforceSingleKing(board: Array<CharArray>, kingScore: Array<IntArray>, isWhite: Boolean) {
        val target = if (isWhite) 'K' else 'k'
        val coords = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (board[r][c] == target) coords.add(r to c)
            }
        }
        if (coords.size > 1) {
            val best = coords.maxByOrNull { (r, c) -> kingScore[r][c] } ?: coords[0]
            for (pos in coords) {
                if (pos != best) {
                    board[pos.first][pos.second] = if (isWhite) 'Q' else 'q'
                }
            }
        }
    }

    private fun findFallbackKingSquare(
        board: Array<CharArray>,
        kingScore: Array<IntArray>,
        isWhite: Boolean
    ): Pair<Int, Int>? {
        var bestPos: Pair<Int, Int>? = null
        var bestScore = -999
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val p = board[r][c]
                if (p != '.' && p.isUpperCase() == isWhite && p.lowercaseChar() in charArrayOf('q', 'b', 'r')) {
                    val s = kingScore[r][c] + if ((r == 0 || r == 7) && c == 4) 25 else 0
                    if (s > bestScore) {
                        bestScore = s
                        bestPos = r to c
                    }
                }
            }
        }
        return bestPos
    }

    private fun detectContinueButton(bmp: Bitmap): Boolean {
        val yStart = (bmp.height * 0.85f).toInt()
        val yEnd = (bmp.height * 0.96f).toInt()
        val xStart = (bmp.width * 0.15f).toInt()
        val xEnd = (bmp.width * 0.85f).toInt()

        var greenPixels = 0
        var total = 0
        val stepX = max(8, (xEnd - xStart) / 25)
        val stepY = max(6, (yEnd - yStart) / 8)

        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (g > 170 && g > r + 55 && g > b + 80) {
                    greenPixels++
                }
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && (greenPixels.toFloat() / total) > 0.35f
    }

    private fun buildFen(board: Array<CharArray>, whiteAtBottom: Boolean): String {
        val sb = StringBuilder()
        val rows = if (whiteAtBottom) (0..7) else (7 downTo 0)
        val cols = if (whiteAtBottom) (0..7) else (7 downTo 0)

        var firstRow = true
        for (r in rows) {
            if (!firstRow) sb.append('/')
            firstRow = false
            var empty = 0
            for (c in cols) {
                val p = board[r][c]
                if (p == '.') {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(p)
                }
            }
            if (empty > 0) sb.append(empty)
        }
        val sideToMove = if (whiteAtBottom) "w" else "b"
        sb.append(" $sideToMove KQkq - 0 1")
        return sb.toString()
    }
}
