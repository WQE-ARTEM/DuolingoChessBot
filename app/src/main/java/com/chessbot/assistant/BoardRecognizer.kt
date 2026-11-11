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

    private data class PieceCandidate(
        val r: Int,
        val c: Int,
        val color: Char,           // 'W' или 'B'
        val isPawn: Boolean,
        val kingScore: Int,
        val queenScore: Int,
        val rookScore: Int,
        val bishopScore: Int,
        val knightScore: Int
    )

    fun isFrameStable(prev: Bitmap?, curr: Bitmap, topPx: Int, sizePx: Int): Boolean {
        if (prev == null || prev.width != curr.width || prev.height != curr.height) return false
        val safeTop = topPx.coerceIn(0, max(0, curr.height - 10))
        val safeSize = min(sizePx, curr.height - safeTop)
        if (safeSize < 80) return true

        var diff = 0
        var total = 0
        val step = max(16, safeSize / 18)
        var y = safeTop + step
        while (y < safeTop + safeSize - step) {
            var x = step
            while (x < curr.width - step) {
                val p1 = prev.getPixel(x, y)
                val p2 = curr.getPixel(x, y)
                if (abs(Color.red(p1) - Color.red(p2)) +
                    abs(Color.green(p1) - Color.green(p2)) +
                    abs(Color.blue(p1) - Color.blue(p2)) > 50
                ) diff++
                total++
                x += step
            }
            y += step
        }
        return total == 0 || (diff.toFloat() / total) < 0.055f
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
            return ScanResult(board, "", false, "Сетка мала", continueActive, whiteAtBottom)
        }

        val boardPixels = IntArray(size * size)
        bitmap.getPixels(boardPixels, 0, size, left, top, size, size)

        val cellSize = size / 8f
        val candidates = ArrayList<PieceCandidate>(32)

        for (r in 0..7) {
            for (c in 0..7) {
                val cand = analyzeCell(boardPixels, size, c * cellSize, r * cellSize, cellSize, r, c)
                if (cand != null) {
                    candidates.add(cand)
                }
            }
        }

        if (candidates.size < 2) {
            return ScanResult(board, "", false, "Ожидание доски...", continueActive, whiteAtBottom)
        }

        // 1. Авто-определение стороны снизу (Белые или Чёрные)
        var whiteBottomScore = 0
        var blackBottomScore = 0
        for (cand in candidates) {
            if (cand.color == 'W') {
                if (cand.r >= 5) whiteBottomScore += (if (cand.r >= 6) 4 else 1)
                if (cand.r <= 2) blackBottomScore += (if (cand.r <= 1) 4 else 1)
            } else {
                if (cand.r <= 2) whiteBottomScore += (if (cand.r <= 1) 4 else 1)
                if (cand.r >= 5) blackBottomScore += (if (cand.r >= 6) 4 else 1)
            }
        }

        val detectedWhiteBottom = if (abs(whiteBottomScore - blackBottomScore) >= 3) {
            whiteBottomScore > blackBottomScore
        } else {
            whiteAtBottom
        }

        // 2. Распределяем фигуры для Белых и Чёрных с учётом точной геометрии и шахматных лимитов
        assignSidePieces(candidates.filter { it.color == 'W' }, isWhite = true, detectedWhiteBottom, board)
        assignSidePieces(candidates.filter { it.color == 'B' }, isWhite = false, detectedWhiteBottom, board)

        val fen = buildFen(board, detectedWhiteBottom)
        val sideStr = if (detectedWhiteBottom) "Белые" else "Чёрные"

        return ScanResult(
            board = board,
            fen = fen,
            isValid = true,
            statusNote = "OK ($sideStr, ${candidates.size} фиг.)",
            continueButtonActive = continueActive,
            detectedWhiteAtBottom = detectedWhiteBottom
        )
    }

    private fun assignSidePieces(
        sideCandidates: List<PieceCandidate>,
        isWhite: Boolean,
        whiteAtBottom: Boolean,
        board: Array<CharArray>
    ) {
        if (sideCandidates.isEmpty()) return

        val homeBackRow = if (isWhite == whiteAtBottom) 7 else 0
        val kingCol = if (whiteAtBottom) 4 else 3
        val queenCol = if (whiteAtBottom) 3 else 4

        // А) Сначала выбираем ровно ОДНОГО Короля (с максимальным kingScore среди не-пешек)
        val nonPawns = sideCandidates.filter { !it.isPawn }.ifEmpty { sideCandidates }
        val bestKing = nonPawns.maxByOrNull { cand ->
            var bonus = 0
            if (cand.r == homeBackRow && cand.c == kingCol) bonus += 18
            // Рокировочные поля короля (c1/g1/c8/g8)
            if (cand.r == homeBackRow && (cand.c == 1 || cand.c == 2 || cand.c == 5 || cand.c == 6)) bonus += 8
            cand.kingScore + bonus
        }!!

        board[bestKing.r][bestKing.c] = if (isWhite) 'K' else 'k'

        // Б) Все очевидные пешки сразу получают 'P' / 'p' (и никогда не превращаются в коней или ферзей!)
        val majorCandidates = ArrayList<PieceCandidate>()
        for (cand in sideCandidates) {
            if (cand === bestKing) continue
            if (cand.isPawn && cand.r in 1..6) {
                board[cand.r][cand.c] = if (isWhite) 'P' else 'p'
            } else {
                majorCandidates.add(cand)
            }
        }

        // В) Классифицируем каждую крупную фигуру по её лучшему счёту формы + лёгкий бонус за родную стартовую клетку
        var qCount = 0
        var rCount = 0
        var bCount = 0
        var nCount = 0

        // Сортируем по уверенности распознавания, чтобы самые чёткие фигуры первыми заняли лимиты (1 Ферзь, 2 Ладьи, 2 Слона, 2 Коня)
        val sortedMajors = majorCandidates.sortedByDescending { cand ->
            maxOf(cand.queenScore, cand.rookScore, cand.bishopScore, cand.knightScore)
        }

        for (cand in sortedMajors) {
            val onBackRow = cand.r == homeBackRow
            val qScore = cand.queenScore + (if (onBackRow && cand.c == queenCol) 14 else 0)
            val rScore = cand.rookScore + (if (onBackRow && (cand.c == 0 || cand.c == 7)) 14 else 0)
            val bScore = cand.bishopScore + (if (onBackRow && (cand.c == 2 || cand.c == 5)) 14 else 0)
            val nScore = cand.knightScore + (if (onBackRow && (cand.c == 1 || cand.c == 6)) 14 else 0)

            val rankedTypes = listOf(
                'q' to qScore,
                'r' to rScore,
                'b' to bScore,
                'n' to nScore
            ).sortedByDescending { it.second }

            var chosen = rankedTypes[0].first
            for ((type, _) in rankedTypes) {
                val allowed = when (type) {
                    'q' -> qCount < 1
                    'r' -> rCount < 2
                    'b' -> bCount < 2
                    'n' -> nCount < 2
                    else -> true
                }
                if (allowed) {
                    chosen = type
                    break
                }
            }

            when (chosen) {
                'q' -> qCount++
                'r' -> rCount++
                'b' -> bCount++
                'n' -> nCount++
            }

            board[cand.r][cand.c] = if (isWhite) chosen.uppercaseChar() else chosen
        }
    }

    private fun analyzeCell(
        boardPixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        row: Int,
        col: Int
    ): PieceCandidate? {
        val N = 32

        // 1. Замеряем цвет фона самой клетки по углам
        var bgRSum = 0; var bgGSum = 0; var bgBSum = 0; var bgLumSum = 0; var bgCount = 0
        val cornerPts = arrayOf(
            2 to 29, 3 to 29, 28 to 29, 29 to 29,
            27 to 2, 28 to 2, 29 to 3, 2 to 25, 29 to 25
        )
        for ((gx, gy) in cornerPts) {
            val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val c = boardPixels[py * stride + px]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            if (!isOverlayGraphic(r, g, b)) {
                bgRSum += r; bgGSum += g; bgBSum += b
                bgLumSum += (r * 299 + g * 587 + b * 114) / 1000
                bgCount++
            }
        }

        val defaultLum = if ((row + col) % 2 == 0) 54 else 34
        val bgLum = if (bgCount > 0) bgLumSum / bgCount else defaultLum
        val bgR = if (bgCount > 0) bgRSum / bgCount else defaultLum
        val bgG = if (bgCount > 0) bgGSum / bgCount else defaultLum
        val bgB = if (bgCount > 0) bgBSum / bgCount else defaultLum

        val lumGrid = Array(N) { IntArray(N) { bgLum } }
        val fg = Array(N) { BooleanArray(N) }
        val overlayMask = Array(N) { BooleanArray(N) }
        val rowMaxLum = IntArray(N) { bgLum }

        var fgCount = 0
        var whiteVotes = 0

        for (gy in 2 until N - 2) {
            for (gx in 3 until N - 3) {
                // Игнорируем левый верхний угол с текстовой подписью оверлея (wP, bN и т.д.)
                if (gx <= 10 && gy <= 7) continue

                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = boardPixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                if (isOverlayGraphic(r, g, b)) {
                    overlayMask[gy][gx] = true
                    continue
                }

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                lumGrid[gy][gx] = lum

                val colorDist = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
                val isPiece = (lum >= 164 && r >= 120) ||
                        (lum >= max(72, bgLum + 20) && b >= r + 4 && colorDist >= 50)

                if (isPiece) {
                    fg[gy][gx] = true
                    fgCount++
                    if (lum > rowMaxLum[gy]) rowMaxLum[gy] = lum
                    if (lum >= 168 && r >= 128) whiteVotes++
                }
            }
        }

        // Залечиваем пиксели фигуры, перекрытые зелёной стрелкой или оранжевым пунктиром
        for (gy in 3 until N - 3) {
            for (gx in 4 until N - 4) {
                if (overlayMask[gy][gx]) {
                    var neighbors = 0
                    if (fg[gy][gx - 1] || fg[gy][max(0, gx - 2)]) neighbors++
                    if (fg[gy][gx + 1] || fg[gy][min(N - 1, gx + 2)]) neighbors++
                    if (fg[gy - 1][gx]) neighbors++
                    if (fg[gy + 1][gx]) neighbors++
                    if (neighbors >= 2) {
                        fg[gy][gx] = true
                        fgCount++
                        lumGrid[gy][gx] = rowMaxLum[gy]
                    }
                }
            }
        }

        var baseCount = 0
        var bodyCount = 0
        var topY = 31
        var bottomY = 0
        val rowLeft = IntArray(N) { 31 }
        val rowRight = IntArray(N) { 0 }
        val rowWidth = IntArray(N)

        for (gy in 2 until N - 2) {
            for (gx in 3 until N - 3) {
                if (fg[gy][gx]) {
                    if (gy in 20..27) baseCount++
                    if (gy in 9..19) bodyCount++
                    if (gy < topY) topY = gy
                    if (gy > bottomY) bottomY = gy
                    if (gx < rowLeft[gy]) rowLeft[gy] = gx
                    if (gx > rowRight[gy]) rowRight[gy] = gx
                }
            }
            if (rowRight[gy] >= rowLeft[gy]) {
                rowWidth[gy] = rowRight[gy] - rowLeft[gy] + 1
            }
        }

        // Отсекаем пустые поля и кружки-подсказки возможных ходов
        if (fgCount < 45 || baseCount < 13 || bodyCount < 16 || bottomY <= topY + 5) {
            return null
        }

        while (topY < bottomY && rowWidth[topY] < 2) topY++
        val h = bottomY - topY + 1
        if (h < 9) return null

        val pieceColor = if (whiteVotes >= fgCount * 0.35f) 'W' else 'B'

        fun avgWidth(rel1: Float, rel2: Float): Float {
            val y1 = (topY + rel1 * (h - 1)).toInt().coerceIn(topY, bottomY)
            val y2 = (topY + rel2 * (h - 1)).toInt().coerceIn(y1, bottomY)
            var sum = 0; var cnt = 0
            for (y in y1..y2) { sum += rowWidth[y]; cnt++ }
            return if (cnt > 0) sum.toFloat() / cnt else 0f
        }

        fun maxWidth(rel1: Float, rel2: Float): Int {
            val y1 = (topY + rel1 * (h - 1)).toInt().coerceIn(topY, bottomY)
            val y2 = (topY + rel2 * (h - 1)).toInt().coerceIn(y1, bottomY)
            var m = 0
            for (y in y1..y2) if (rowWidth[y] > m) m = rowWidth[y]
            return m
        }

        val wVeryTop = avgWidth(0.0f, 0.14f)
        val wUpper = avgWidth(0.15f, 0.36f)
        val wMid = avgWidth(0.36f, 0.56f)
        val wLower = avgWidth(0.60f, 0.80f)
        val maxUpperW = maxWidth(0.10f, 0.52f)

        // 1. Точный детектор ПЕШКИ (Pawn):
        // У пешки низкий рост (topY >= 6, h <= 20), узкая круглая голова (wUpper <= 13.5, maxUpperW <= 14) и малая площадь
        val isPawn = row in 1..6 && (
                (topY >= 6 && maxUpperW <= 14 && fgCount <= 205) ||
                (h <= 19 && wUpper <= 13.2f && wMid <= 13.8f) ||
                (fgCount <= 178 && maxUpperW <= 14)
        )

        // 2. Признаки КОНЯ (Knight): выступающая морда слева сверху и пустой вырез под челюстью слева внизу
        val ySnout1 = (topY + h * 0.18f).toInt().coerceIn(topY, bottomY)
        val ySnout2 = (topY + h * 0.42f).toInt().coerceIn(ySnout1, bottomY)
        val snoutLeft = (ySnout1..ySnout2).minOfOrNull { rowLeft[it] } ?: 16

        val yJaw1 = (topY + h * 0.48f).toInt().coerceIn(topY, bottomY)
        val yJaw2 = (topY + h * 0.72f).toInt().coerceIn(yJaw1, bottomY)
        var jawLeftSum = 0f
        var jawCenterSum = 0f
        var jawRows = 0
        var leftJawMass = 0
        var rightNeckMass = 0
        for (y in yJaw1..yJaw2) {
            if (rowRight[y] >= rowLeft[y]) {
                jawLeftSum += rowLeft[y]
                jawCenterSum += (rowLeft[y] + rowRight[y]) * 0.5f
                jawRows++
            }
            for (x in 4..14) if (fg[y][x]) leftJawMass++
            for (x in 16..26) if (fg[y][x]) rightNeckMass++
        }
        val avgJawLeft = if (jawRows > 0) jawLeftSum / jawRows else 16f
        val avgJawCenter = if (jawRows > 0) jawCenterSum / jawRows else 15.5f
        val jawCut = avgJawLeft - snoutLeft
        val neckAsym = rightNeckMass - leftJawMass

        // 3. Внутренние вырезы (крест Короля, овал Ладьи, зубцы Ферзя, прорезь Слона)
        var innerShadedTop = 0
        var centerCrossShaded = 0
        val yHole1 = (topY + h * 0.10f).toInt().coerceIn(topY, bottomY)
        val yHole2 = (topY + h * 0.34f).toInt().coerceIn(yHole1, bottomY)
        for (y in yHole1..yHole2) {
            val l = rowLeft[y]
            val r = rowRight[y]
            if (r - l >= 8) {
                val rim = rowMaxLum[y]
                for (x in (l + 2)..(r - 2)) {
                    if (!overlayMask[y][x] && lumGrid[y][x] <= rim - 24) {
                        innerShadedTop++
                        if (x in 13..18) centerCrossShaded++
                    }
                }
            }
        }

        // Подсчёт 3 зубцов короны Ферзя на верхушке (горизонтальные переходы фон-фигура-фон)
        var crownSpikesRows = 0
        val ySpike1 = (topY + 1).coerceAtMost(bottomY)
        val ySpike2 = (topY + max(2, h / 5)).coerceAtMost(bottomY)
        for (y in ySpike1..ySpike2) {
            var segments = 0
            var inSeg = false
            for (x in 5..26) {
                if (fg[y][x] && !inSeg) {
                    segments++
                    inSeg = true
                } else if (!fg[y][x]) {
                    inSeg = false
                }
            }
            if (segments >= 3) crownSpikesRows++
        }

        // Диагональная прорезь Слона слева в верхней части купола
        var bishopSlit = 0
        val ySlit1 = (topY + h * 0.20f).toInt().coerceIn(topY, bottomY)
        val ySlit2 = (topY + h * 0.45f).toInt().coerceIn(ySlit1, bottomY)
        for (y in ySlit1..ySlit2) {
            val l = rowLeft[y]
            val r = rowRight[y]
            if (r - l >= 8) {
                for (x in (l + 1)..min(r - 2, l + 6)) {
                    if (!fg[y][x] && fg[y][min(N - 1, x + 3)]) bishopSlit++
                }
            }
        }

        // Вычисляем очки для каждого из 5 классов старших фигур:
        // КОРОЛЬ: узкая верхушка с крестом + самое широкое сердцевидное тело (wMid >= 18) + тёмный крест по центру
        var kingScore = 0
        if (wMid >= 17.5f) kingScore += ((wMid - 16f) * 14f).toInt()
        if (wVeryTop in 4.0f..11.5f && wMid - wVeryTop >= 6.5f) kingScore += 35
        kingScore += centerCrossShaded * 9
        if (jawCut >= 2.8f && neckAsym >= 11) kingScore -= 60 // конь не король

        // КОНЬ: сильный вырез под челюстью слева + смещение шеи вправо
        var knightScore = 0
        if (jawCut >= 2.5f) knightScore += (jawCut * 18f).toInt()
        if (neckAsym >= 8) knightScore += neckAsym * 4
        if (avgJawCenter >= 16.4f) knightScore += ((avgJawCenter - 15.5f) * 22f).toInt()
        if (snoutLeft <= 8) knightScore += 20

        // СЛОН: узкая макушка (wVeryTop <= 8.5), купол уже живота (wUpper < wMid - 1.8) + прорезь слева
        var bishopScore = 0
        if (wMid > wUpper + 1.5f) bishopScore += ((wMid - wUpper) * 18f).toInt()
        if (wVeryTop <= 8.5f) bishopScore += 25
        bishopScore += bishopSlit * 10
        if (wUpper >= 16.0f) bishopScore -= 40 // у слона не бывает широкой шапки ладьи/ферзя

        // ФЕРЗЬ: широкая корона сверху (wUpper >= 16.5), сильно сужающаяся к ножке внизу (wUpper - wLower >= 3.0) + зубцы
        val crownTaper = wUpper - wLower
        var queenScore = 0
        if (wUpper >= 16.0f) queenScore += ((wUpper - 14f) * 12f).toInt()
        if (crownTaper >= 2.5f) queenScore += (crownTaper * 14f).toInt()
        queenScore += crownSpikesRows * 18
        if (jawCut >= 3.0f && neckAsym >= 12) queenScore -= 50

        // ЛАДЬЯ: прямая башня, где верх и нижняя подставка почти одной ширины (abs(wUpper - wLower) <= 2.5) + плоский верх с овалом
        var rookScore = 0
        if (wUpper >= 14.5f && wLower >= 14.5f && abs(wUpper - wLower) <= 2.8f) {
            rookScore += 45 - (abs(wUpper - wLower) * 10f).toInt()
        }
        if (wVeryTop >= 10.5f) rookScore += 25
        rookScore += innerShadedTop * 5
        if (crownTaper >= 3.8f) rookScore -= 35 // это ферзь, а не ладья
        if (jawCut >= 3.0f && neckAsym >= 12) rookScore -= 50

        return PieceCandidate(
            r = row,
            c = col,
            color = pieceColor,
            isPawn = isPawn,
            kingScore = kingScore,
            queenScore = queenScore,
            rookScore = rookScore,
            bishopScore = bishopScore,
            knightScore = knightScore
        )
    }

    private fun isOverlayGraphic(r: Int, g: Int, b: Int): Boolean {
        // Ярко-зелёная стрелка оверлея
        if (r < 95 && g > 180 && g > r + 85 && g > b + 50) return true
        // Оранжевый пунктир ответного хода
        if (r > 175 && g in 95..215 && b < 90) return true
        return false
    }

    private fun detectContinueButton(bmp: Bitmap): Boolean {
        val yStart = (bmp.height * 0.85f).toInt()
        val yEnd = (bmp.height * 0.96f).toInt()
        val xStart = (bmp.width * 0.15f).toInt()
        val xEnd = (bmp.width * 0.85f).toInt()
        var green = 0; var total = 0
        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (g > 170 && g > r + 55 && g > b + 80) green++
                total++; x += 16
            }
            y += 10
        }
        return total > 0 && (green.toFloat() / total) > 0.35f
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
                if (p == '.') empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(p)
                }
            }
            if (empty > 0) sb.append(empty)
        }
        sb.append(if (whiteAtBottom) " w KQkq - 0 1" else " b KQkq - 0 1")
        return sb.toString()
    }
}
