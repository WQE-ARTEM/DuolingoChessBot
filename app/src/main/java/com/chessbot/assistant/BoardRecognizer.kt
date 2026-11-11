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

        // Оставляем ровно по одному королю на каждую сторону (лучшего по очкам креста/формы)
        enforceSingleKing(board, kingScore, isWhite = true)
        enforceSingleKing(board, kingScore, isWhite = false)

        // Проверка на начальную позицию партии (когда оба ряда фигур 0 и 7 заполнены, а центр 2..5 пуст)
        val rank0Count = (0 until 8).count { board[0][it] != '.' }
        val rank7Count = (0 until 8).count { board[7][it] != '.' }
        val emptyCenter = (2..5).sumOf { r -> (0 until 8).count { c -> board[r][c] == '.' } }

        if (rank0Count == 8 && rank7Count == 8 && emptyCenter >= 30) {
            val backRank = charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')
            val bottomWhite = board[7].count { it.isUpperCase() } >= 4
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
        val isLightSquare = (row + col) % 2 == 0

        // Семплируем углы клетки для эталона фона доски (исключая зелёные линии и текст оверлея)
        var bgLumSum = 0
        var bgCount = 0
        val cornerSamples = arrayOf(
            2 to 29, 3 to 29, 28 to 29, 29 to 29,
            28 to 2, 29 to 2, 29 to 3, 2 to 24, 29 to 24
        )
        for ((gx, gy) in cornerSamples) {
            val px = (cellX + (gx + 0.5f) * cellW / N).toInt().coerceIn(0, bmp.width - 1)
            val py = (cellY + (gy + 0.5f) * cellH / N).toInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(px, py)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (!isOverlayColor(r, g, b)) {
                bgLumSum += (r * 299 + g * 587 + b * 114) / 1000
                bgCount++
            }
        }
        val bgLum = if (bgCount > 0) (bgLumSum / bgCount).coerceIn(24, 68) else if (isLightSquare) 54 else 35

        val lumGrid = Array(N) { IntArray(N) { bgLum } }
        val fg = Array(N) { BooleanArray(N) }

        var whiteVotes = 0
        var blackVotes = 0

        for (gy in 2 until N - 1) {
            for (gx in 2 until N - 2) {
                // Игнорируем левый верхний угол, где рисуется метка оверлея (wP, bQ и т.д.)
                if (gx <= 10 && gy <= 7) continue

                val px = (cellX + (gx + 0.5f) * cellW / N).toInt().coerceIn(0, bmp.width - 1)
                val py = (cellY + (gy + 0.5f) * cellH / N).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(px, py)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                // Фильтруем зелёную стрелку и оранжевый пунктир оверлея
                if (isOverlayColor(r, g, b)) {
                    lumGrid[gy][gx] = -1
                    continue
                }

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                lumGrid[gy][gx] = lum

                // Игнорируем кружок подсказки хода по центру пустой клетки (небольшая точка в gy 13..18, gx 13..18)
                if (lum >= 158 && b >= r - 15) {
                    whiteVotes++
                } else if (lum in 68..157 && b >= r + 3 && lum >= bgLum + 15) {
                    blackVotes++
                }
            }
        }

        // Порог присутствия фигуры (отсекает серые кружки-подсказки на пустых полях, у которых площадь ~12-20 пикселей)
        val isWhitePiece: Boolean
        if (whiteVotes >= 45 && whiteVotes >= blackVotes) {
            isWhitePiece = true
        } else if (blackVotes >= 40) {
            isWhitePiece = false
        } else {
            return '.' to -1000
        }

        var totalFg = 0
        var topY = 31
        var bottomY = 0
        var minX = 31
        var maxX = 0
        val rowLeft = IntArray(N) { 31 }
        val rowRight = IntArray(N) { 0 }
        val rowWidth = IntArray(N)
        val rowCount = IntArray(N)

        for (gy in 2 until N - 1) {
            for (gx in 2 until N - 2) {
                if (gx <= 10 && gy <= 7) continue
                val lum = lumGrid[gy][gx]
                if (lum < 0) continue

                val isPiecePixel = if (isWhitePiece) {
                    lum >= 145
                } else {
                    lum in 66..162 && lum >= bgLum + 14
                }

                if (isPiecePixel) {
                    fg[gy][gx] = true
                    totalFg++
                    rowCount[gy]++
                    if (gy < topY) topY = gy
                    if (gy > bottomY) bottomY = gy
                    if (gx < minX) minX = gx
                    if (gx > maxX) maxX = gx
                    if (gx < rowLeft[gy]) rowLeft[gy] = gx
                    if (gx > rowRight[gy]) rowRight[gy] = gx
                }
            }
            if (rowRight[gy] >= rowLeft[gy]) {
                rowWidth[gy] = rowRight[gy] - rowLeft[gy] + 1
            }
        }

        val pieceHeight = bottomY - topY + 1
        val pieceWidth = maxX - minX + 1
        if (totalFg < 44 || pieceHeight < 9 || pieceWidth < 8) return '.' to -1000

        // Уточняем реальную верхушку фигуры (игнорируем одиночные шумовые пиксели)
        while (topY < bottomY && rowWidth[topY] < 2) topY++
        val h = bottomY - topY + 1
        if (h < 9) return '.' to -1000

        // Вспомогательная функция ширины фигуры на относительной высоте (0.0 = макушка, 1.0 = низ базы)
        fun widthAt(relY: Float): Int {
            val y = (topY + relY * (h - 1)).toInt().coerceIn(topY, bottomY)
            return rowWidth[y]
        }

        fun avgWidth(relStart: Float, relEnd: Float): Float {
            val y1 = (topY + relStart * (h - 1)).toInt().coerceIn(topY, bottomY)
            val y2 = (topY + relEnd * (h - 1)).toInt().coerceIn(y1, bottomY)
            var sum = 0
            var cnt = 0
            for (y in y1..y2) {
                sum += rowWidth[y]
                cnt++
            }
            return if (cnt > 0) sum.toFloat() / cnt else 0f
        }

        val wTop = avgWidth(0.08f, 0.25f)      // верхняя шапка / зубцы / голова
        val wUpperMid = avgWidth(0.28f, 0.48f) // плечи / тело
        val wLowerMid = avgWidth(0.50f, 0.70f) // талия / шея
        val wBase = avgWidth(0.75f, 0.92f)     // подставка

        // 1. ПЕШКА (Pawn):
        // В Duolingo пешка заметно ниже всех остальных фигур (topY >= 7..10, h <= 20) и имеет круглую голову (wTop 8..12),
        // переходящую в узкую шею (wLowerMid <= wUpperMid) и компактную базу (wBase <= 16).
        // У слона (Bishop) h >= 22..25 и широкое пузо (wUpperMid >= 14.5).
        if (row in 1..6) {
            val isPawnHeight = topY >= 7 && h <= 20
            val isPawnArea = totalFg <= 215
            val isNotBishopBelly = wUpperMid <= 13.8f && wBase <= 16.5f
            if ((isPawnHeight && isNotBishopBelly) || (isPawnArea && topY >= 8 && wTop <= 12.2f && wUpperMid <= 13.2f)) {
                return (if (isWhitePiece) 'P' else 'p') to -500
            }
        }

        // 2. КОНЬ (Knight):
        // У коня голова смотрит влево (в верхней половине левый край выступает влево),
        // а под мордой слева (относительная высота 0.48..0.75) большой вырез фона, тогда как справа массивная спина/шея.
        var leftLowerMass = 0
        var rightLowerMass = 0
        val midX = (minX + maxX) / 2
        val yJawStart = (topY + h * 0.45f).toInt()
        val yJawEnd = (topY + h * 0.74f).toInt()
        for (y in yJawStart..yJawEnd) {
            for (x in minX..maxX) {
                if (fg[y][x]) {
                    if (x < midX) leftLowerMass++ else rightLowerMass++
                }
            }
        }
        // На уровне 0.55..0.70 левая граница фигуры резко уходит вправо (под челюстью пустота)
        val topLeftEdge = ((topY + h * 0.22f).toInt()..(topY + h * 0.40f).toInt()).minOfOrNull { rowLeft[it] } ?: minX
        val jawLeftEdge = ((topY + h * 0.52f).toInt()..(topY + h * 0.68f).toInt()).maxOfOrNull { rowLeft[it] } ?: minX
        val jawIndent = jawLeftEdge - topLeftEdge

        if (rightLowerMass >= leftLowerMass + 12 && jawIndent >= 3) {
            return (if (isWhitePiece) 'N' else 'n') to -300
        }

        // Считаем НАСТОЯЩИЕ тёмные отверстия цвета фона доски внутри контура фигуры
        // (не путать с серо-синей заливкой чёрных фигур! Тёмная дырка имеет яркость фона <= bgLum + 10)
        var topDarkHole = 0   // Овальная дыра сверху у Ладьи (relY 0.10..0.34)
        var crossDarkHole = 0 // Тёмный крестик внутри Короля (relY 0.14..0.38)
        val yHole1 = (topY + h * 0.10f).toInt()
        val yHole2 = (topY + h * 0.36f).toInt()
        for (y in yHole1..yHole2) {
            val l = rowLeft[y]
            val r = rowRight[y]
            if (r - l >= 8) {
                for (x in (l + 3)..(r - 3)) {
                    val lum = lumGrid[y][x]
                    if (lum in 0..(bgLum + 11)) {
                        topDarkHole++
                        if (x in (midX - 2)..(midX + 2)) crossDarkHole++
                    }
                }
            }
        }

        // Проверяем диагональную прорезь СЛОНА (Bishop) слева-сверху (relY 0.25..0.52)
        var bishopNotch = 0
        val yNotch1 = (topY + h * 0.24f).toInt()
        val yNotch2 = (topY + h * 0.52f).toInt()
        for (y in yNotch1..yNotch2) {
            val l = rowLeft[y]
            val r = rowRight[y]
            if (r - l >= 8) {
                // Прорезь у слона идёт с левой стороны внутрь купола
                for (x in (l + 1)..min(r - 2, l + 6)) {
                    if (!fg[y][x] && fg[y][min(N - 1, x + 3)]) {
                        bishopNotch++
                    }
                }
            }
        }

        // Оценка КОРОЛЯ (King):
        // У короля на самом верху узкий крестик (ширина макушки 3..7 пикселей), под ним очень широкое сердцевидное тело (wUpperMid >= 17.5)
        // и тёмный крест внутри верхней части.
        val veryTopWidth = avgWidth(0.0f, 0.10f)
        var kScore = 0
        if (veryTopWidth in 2.5f..8.5f && wUpperMid >= 16.5f) kScore += 35
        if (crossDarkHole >= 3) kScore += 35
        if (wUpperMid > wTop + 3.0f) kScore += 25
        if (h >= 23) kScore += 15
        if ((row == 0 || row == 7) && col == 4) kScore += 15

        // 3. СЛОН (Bishop):
        // Острая макушка-шарик сверху (wTop заметно уже пуза: wTop < wUpperMid - 2.5), круглое пузо (wUpperMid 14..18),
        // плюс характерная прорезь слева (bishopNotch) и отсутствие широкого тёмного овала ладьи.
        if (wTop <= wUpperMid - 2.2f && wUpperMid <= 18.2f && (bishopNotch >= 3 || veryTopWidth <= 6.5f) && kScore < 65) {
            return (if (isWhitePiece) 'B' else 'b') to kScore
        }

        // 4. ЛАДЬЯ (Rook):
        // Цилиндрическая башня: плоский широкий верх (veryTopWidth >= 10), ширина верха почти равна ширине середины и базы,
        // а сверху внутри крыши крупный горизонтальный тёмный овал (topDarkHole >= 6).
        val isTowerShape = wTop >= 13.0f && abs(wTop - wUpperMid) <= 2.5f && veryTopWidth >= 9.5f
        if (isTowerShape && topDarkHole >= 5 && wUpperMid <= 19.0f) {
            return (if (isWhitePiece) 'R' else 'r') to kScore
        }

        // 5. ФЕРЗЬ (Queen) vs КОРОЛЬ (King):
        // У ферзя широкая корона с зубцами в верхней части (wTop >= 15.5, шире талии wLowerMid)
        if (kScore >= 65 && wUpperMid > wTop + 2.0f) {
            return (if (isWhitePiece) 'K' else 'k') to kScore
        }

        if (wTop >= 15.0f && wTop >= wLowerMid + 1.2f) {
            return (if (isWhitePiece) 'Q' else 'q') to kScore
        }

        if (kScore >= 55) {
            return (if (isWhitePiece) 'K' else 'k') to kScore
        }

        if (isTowerShape) {
            return (if (isWhitePiece) 'R' else 'r') to kScore
        }

        if (wTop < wUpperMid - 1.5f) {
            return (if (isWhitePiece) 'B' else 'b') to kScore
        }

        return (if (isWhitePiece) 'Q' else 'q') to kScore
    }

    private fun isOverlayColor(r: Int, g: Int, b: Int): Boolean {
        // Ярко-зелёная стрелка или рамка
        if (g > r + 35 && g > b + 20) return true
        // Оранжево-жёлтый пунктир ответа (ponder move)
        if (r > 170 && g in 100..210 && b < 90) return true
        return false
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
                    board[pos.first][pos.second] = if (isWhite) 'R' else 'r'
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
