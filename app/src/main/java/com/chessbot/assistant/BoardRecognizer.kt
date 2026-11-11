package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    private var memoryBoard = Array(8) { CharArray(8) { '.' } }
    private var memoryOrientation: Boolean? = null
    private var memoryInitialized = false

    data class ScanResult(
        val board: Array<CharArray>,
        val fen: String,
        val isValid: Boolean,
        val statusNote: String,
        val continueButtonActive: Boolean = false,
        val detectedWhiteAtBottom: Boolean = true
    )

    private data class CellFeatures(
        val color: Char,           // '.' = пусто, 'W' = белая, 'B' = чёрная
        val fgCount: Int = 0,
        val baseCount: Int = 0,
        val topY: Int = 31,
        val height: Int = 0,
        val wVeryTop: Float = 0f,
        val wTop: Float = 0f,
        val wMid: Float = 0f,
        val wWaist: Float = 0f,
        val jawAsymmetry: Int = 0,
        val snoutLeft: Int = 31,
        val innerShadedTop: Int = 0,
        val centerCrossShaded: Int = 0,
        val bishopSlit: Int = 0,
        val visualType: Char = '.',
        val massScore: Int = 0
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
        val emptyBoard = Array(8) { CharArray(8) { '.' } }
        val continueActive = detectContinueButton(bitmap)

        val left = boardLeftPx.coerceIn(0, max(0, bitmap.width - 64))
        val top = boardTopPx.coerceIn(0, max(0, bitmap.height - 64))
        val size = min(boardSizePx, min(bitmap.width - left, bitmap.height - top))
        if (size < 120) {
            return ScanResult(emptyBoard, "", false, "Сетка мала", continueActive, whiteAtBottom)
        }

        // Пакетное чтение всех пикселей доски за 1 вызов (в 20 раз быстрее getPixel)
        val boardPixels = IntArray(size * size)
        bitmap.getPixels(boardPixels, 0, size, left, top, size, size)

        val cellSize = size / 8f
        val cells = Array(8) { r ->
            Array(8) { c ->
                analyzeCell(boardPixels, size, c * cellSize, r * cellSize, cellSize, r, c)
            }
        }

        // 1. Автоматически определяем ориентацию доски (Белые или Чёрные внизу)
        var whiteBottomScore = 0
        var blackBottomScore = 0
        var totalPieces = 0
        for (r in 0..7) {
            for (c in 0..7) {
                when (cells[r][c].color) {
                    'W' -> {
                        totalPieces++
                        if (r >= 5) whiteBottomScore += (if (r >= 6) 4 else 1)
                        if (r <= 2) blackBottomScore += (if (r <= 1) 4 else 1)
                    }
                    'B' -> {
                        totalPieces++
                        if (r <= 2) whiteBottomScore += (if (r <= 1) 4 else 1)
                        if (r >= 5) blackBottomScore += (if (r >= 6) 4 else 1)
                    }
                }
            }
        }

        if (totalPieces < 2) {
            return ScanResult(emptyBoard, "", false, "Ожидание доски...", continueActive, whiteAtBottom)
        }

        val detectedWhiteBottom = if (abs(whiteBottomScore - blackBottomScore) >= 4) {
            whiteBottomScore > blackBottomScore
        } else {
            whiteAtBottom
        }

        // 2. Сброс памяти на старте новой партии/задачи
        val homeMatches = countHomeMatches(cells, detectedWhiteBottom)
        val centerPieces = (2..5).sumOf { r -> (0..7).count { c -> cells[r][c].color != '.' } }

        if (!memoryInitialized || memoryOrientation != detectedWhiteBottom || (homeMatches >= 28 && centerPieces <= 2)) {
            memoryBoard = createStartingBoard(detectedWhiteBottom)
            memoryOrientation = detectedWhiteBottom
            memoryInitialized = true
        }

        // 3. Собираем точную позицию (Силуэт + Инвентарь стартовых рядов + Дельта-память)
        val resultBoard = Array(8) { CharArray(8) { '.' } }
        resolveSidePieces('W', detectedWhiteBottom, cells, memoryBoard, resultBoard)
        resolveSidePieces('B', detectedWhiteBottom, cells, memoryBoard, resultBoard)

        memoryBoard = Array(8) { r -> resultBoard[r].clone() }

        val fen = buildFen(resultBoard, detectedWhiteBottom)
        val sideStr = if (detectedWhiteBottom) "Белые" else "Чёрные"

        return ScanResult(
            board = resultBoard,
            fen = fen,
            isValid = true,
            statusNote = "OK ($sideStr, $totalPieces фиг.)",
            continueButtonActive = continueActive,
            detectedWhiteAtBottom = detectedWhiteBottom
        )
    }

    private fun resolveSidePieces(
        sideColor: Char,
        whiteAtBottom: Boolean,
        cells: Array<Array<CellFeatures>>,
        prevBoard: Array<CharArray>,
        outBoard: Array<CharArray>
    ) {
        val isWhite = sideColor == 'W'
        val startTemplate = createStartingBoard(whiteAtBottom)
        val backRow = if (isWhite == whiteAtBottom) 7 else 0
        val pawnRow = if (isWhite == whiteAtBottom) 6 else 1
        val pawnChar = if (isWhite) 'P' else 'p'

        // Отслеживаем одиночный ход с прошлого кадра
        val prevCoords = mutableListOf<Pair<Int, Int>>()
        val currCoords = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) {
            for (c in 0..7) {
                val p = prevBoard[r][c]
                if (p != '.' && p.isUpperCase() == isWhite) prevCoords.add(r to c)
                if (cells[r][c].color == sideColor) currCoords.add(r to c)
            }
        }

        val leftSquares = prevCoords.filter { (r, c) -> cells[r][c].color != sideColor }
        val enteredSquares = currCoords.filter { (r, c) ->
            val p = prevBoard[r][c]
            p == '.' || p.isUpperCase() != isWhite
        }

        val singleStepMove: Pair<Pair<Int, Int>, Char>? = if (leftSquares.size == 1 && enteredSquares.size == 1) {
            val from = leftSquares[0]
            val to = enteredSquares[0]
            to to prevBoard[from.first][from.second]
        } else null

        // Инвентаризация тыловых рядов (какие фигуры стоят дома, а какие вышли в игру)
        val missingMajors = mutableListOf<Char>()
        var missingPawns = 0

        for (c in 0..7) {
            val homeMajor = startTemplate[backRow][c]
            val cellBack = cells[backRow][c]
            if (cellBack.color == sideColor) {
                val prevP = prevBoard[backRow][c]
                // Если на месте стоит стартовая фигура (или ладья/король после рокировки)
                outBoard[backRow][c] = if (prevP != '.' && prevP.isUpperCase() == isWhite) prevP else homeMajor
            } else {
                missingMajors.add(homeMajor)
            }

            val cellPawn = cells[pawnRow][c]
            if (cellPawn.color == sideColor) {
                // Проверяем, не пришла ли на пешковую горизонталь крупная фигура
                val prevP = prevBoard[pawnRow][c]
                if (cellPawn.visualType.lowercaseChar() != 'p' && cellPawn.massScore >= 245 &&
                    prevP != '.' && prevP.isUpperCase() == isWhite && prevP.lowercaseChar() != 'p'
                ) {
                    outBoard[pawnRow][c] = prevP
                    missingPawns++
                } else {
                    outBoard[pawnRow][c] = pawnChar
                }
            } else {
                missingPawns++
            }
        }

        // Собираем все активные клетки вне стартовых позиций
        val movedSquares = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) {
            for (c in 0..7) {
                if (cells[r][c].color == sideColor && outBoard[r][c] == '.') {
                    movedSquares.add(r to c)
                }
            }
        }

        if (movedSquares.isEmpty()) return

        val remaining = mutableListOf<Pair<Int, Int>>()

        // Шаг А: если был зафиксирован точный одиночный ход с предыдущего кадра
        for (sq in movedSquares) {
            if (singleStepMove != null && sq == singleStepMove.first) {
                val movedCh = singleStepMove.second
                outBoard[sq.first][sq.second] = movedCh
                if (movedCh.lowercaseChar() == 'p') {
                    missingPawns = max(0, missingPawns - 1)
                } else {
                    missingMajors.remove(movedCh)
                }
            } else {
                remaining.add(sq)
            }
        }

        // Шаг Б: сначала отделяем все очевидные ПЕШКИ по росту и компактности
        val nonPawnSquares = mutableListOf<Pair<Int, Int>>()
        for (sq in remaining) {
            val feat = cells[sq.first][sq.second]
            val isPawnRow = sq.first in 1..6
            val looksLikePawn = isPawnRow && (feat.visualType.lowercaseChar() == 'p' || feat.massScore < 222)
            if (looksLikePawn && (missingPawns > 0 || missingMajors.isEmpty())) {
                outBoard[sq.first][sq.second] = pawnChar
                missingPawns = max(0, missingPawns - 1)
            } else {
                nonPawnSquares.add(sq)
            }
        }

        // Шаг В: распределяем вышедшие крупные фигуры из пула missingMajors по лучшему совпадению формы
        val sortedMajors = nonPawnSquares.sortedByDescending { (r, c) -> cells[r][c].massScore }
        for (sq in sortedMajors) {
            val feat = cells[sq.first][sq.second]
            val prevP = prevBoard[sq.first][sq.second]

            // Если в памяти на этой клетке уже стояла фигура из доступного пула — сохраняем её
            if (prevP != '.' && prevP.isUpperCase() == isWhite && missingMajors.contains(prevP)) {
                outBoard[sq.first][sq.second] = prevP
                missingMajors.remove(prevP)
            } else if (missingMajors.isNotEmpty() && feat.massScore >= 215) {
                val bestMatch = matchPieceFromPool(missingMajors, feat, isWhite)
                outBoard[sq.first][sq.second] = bestMatch
                missingMajors.remove(bestMatch)
            } else if (missingPawns > 0 && sq.first in 1..6) {
                outBoard[sq.first][sq.second] = pawnChar
                missingPawns--
            } else if (missingMajors.isNotEmpty()) {
                val bestMatch = matchPieceFromPool(missingMajors, feat, isWhite)
                outBoard[sq.first][sq.second] = bestMatch
                missingMajors.remove(bestMatch)
            } else {
                outBoard[sq.first][sq.second] = feat.visualType
            }
        }

        // Шаг Г: гарантируем ровно одного короля
        val kingChar = if (isWhite) 'K' else 'k'
        val kingPositions = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) {
            for (c in 0..7) {
                if (outBoard[r][c] == kingChar) kingPositions.add(r to c)
            }
        }
        if (kingPositions.isEmpty() && currCoords.isNotEmpty()) {
            val bestK = currCoords.maxByOrNull { (r, c) -> cells[r][c].centerCrossShaded * 10 + cells[r][c].massScore }!!
            outBoard[bestK.first][bestK.second] = kingChar
        } else if (kingPositions.size > 1) {
            val bestK = kingPositions.maxByOrNull { (r, c) -> cells[r][c].centerCrossShaded * 10 + cells[r][c].massScore }!!
            for (pos in kingPositions) {
                if (pos != bestK) outBoard[pos.first][pos.second] = if (isWhite) 'Q' else 'q'
            }
        }
    }

    private fun matchPieceFromPool(pool: List<Char>, feat: CellFeatures, isWhite: Boolean): Char {
        if (pool.size == 1) return pool[0]
        val vLower = feat.visualType.lowercaseChar()
        pool.firstOrNull { it.lowercaseChar() == vLower }?.let { return it }

        // Ранжируем кандидатов в пуле по геометрическим признакам
        return pool.maxByOrNull { candidate ->
            when (candidate.lowercaseChar()) {
                'n' -> feat.jawAsymmetry * 6 + (if (feat.snoutLeft <= 7) 25 else 0)
                'b' -> feat.bishopSlit * 8 + ((feat.wMid - feat.wTop) * 8).toInt()
                'r' -> feat.innerShadedTop * 5 - (abs(feat.wTop - feat.wMid) * 6).toInt()
                'q' -> feat.innerShadedTop * 4 + (feat.wTop * 4).toInt()
                'k' -> feat.centerCrossShaded * 8 + (feat.wMid * 4).toInt()
                else -> 0
            }
        } ?: pool[0]
    }

    private fun analyzeCell(
        boardPixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        row: Int,
        col: Int
    ): CellFeatures {
        val N = 32

        // 1. Определяем базовый цвет клетки по её углам (адаптируется даже к зелёной подсветке хода!)
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
            if (!isSolidArrowLine(r, g, b)) {
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
        val rowMaxLum = IntArray(N) { bgLum }

        var fgCount = 0
        var whiteVotes = 0
        var baseCount = 0
        var bodyCount = 0
        var topY = 31
        var bottomY = 0
        val rowLeft = IntArray(N) { 31 }
        val rowRight = IntArray(N) { 0 }
        val rowWidth = IntArray(N)

        for (gy in 2 until N - 2) {
            for (gx in 3 until N - 3) {
                // Пропускаем зону левого верхнего угла, где оверлей пишет мелкие буквы (wP, bN и т.д.)
                if (gx <= 11 && gy <= 8) continue

                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = boardPixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                // Пропускаем только саму ярко-зелёную линию стрелки и оранжевый пунктир
                if (isSolidArrowLine(r, g, b)) continue

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                lumGrid[gy][gx] = lum

                val colorDist = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
                // Белая фигура (включая стоящую под зелёной подсветкой клетки) или Чёрная серо-синяя фигура
                val isPiece = (lum >= 165 && r >= 125) ||
                        (lum >= max(72, bgLum + 20) && b >= r + 4 && colorDist >= 52)

                if (isPiece) {
                    fg[gy][gx] = true
                    fgCount++
                    if (lum > rowMaxLum[gy]) rowMaxLum[gy] = lum
                    if (lum >= 168 && r >= 130) whiteVotes++
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

        // Отсекаем пустые клетки и серые кружки-подсказки по центру (у кружков нет подставки внизу: baseCount == 0)
        if (fgCount < 46 || baseCount < 13 || bodyCount < 16 || bottomY <= topY + 5) {
            return CellFeatures('.')
        }

        while (topY < bottomY && rowWidth[topY] < 2) topY++
        val h = bottomY - topY + 1
        if (h < 9) return CellFeatures('.')

        val pieceColor = if (whiteVotes >= fgCount * 0.36f) 'W' else 'B'
        val isWhite = pieceColor == 'W'

        fun avgWidth(rel1: Float, rel2: Float): Float {
            val y1 = (topY + rel1 * (h - 1)).toInt().coerceIn(topY, bottomY)
            val y2 = (topY + rel2 * (h - 1)).toInt().coerceIn(y1, bottomY)
            var s = 0; var cnt = 0
            for (y in y1..y2) { s += rowWidth[y]; cnt++ }
            return if (cnt > 0) s.toFloat() / cnt else 0f
        }

        val wVeryTop = avgWidth(0.0f, 0.12f)
        val wTop = avgWidth(0.12f, 0.30f)
        val wMid = avgWidth(0.32f, 0.56f)
        val wWaist = avgWidth(0.58f, 0.75f)

        // Асимметрия коня (челюсть слева пустая на уровне 0.48..0.72, а шея справа массивная)
        var leftJaw = 0
        var rightNeck = 0
        val yJ1 = (topY + h * 0.46f).toInt()
        val yJ2 = (topY + h * 0.72f).toInt()
        for (y in yJ1..yJ2) {
            for (x in 4..14) if (fg[y][x]) leftJaw++
            for (x in 16..26) if (fg[y][x]) rightNeck++
        }
        val jawAsym = rightNeck - leftJaw
        val snoutLeft = ((topY + h * 0.20f).toInt()..(topY + h * 0.42f).toInt()).minOfOrNull { rowLeft[it] } ?: 31

        // Внутренний затенённый рельеф (овал у ладьи, крест у короля, подушка у ферзя)
        var innerShadedTop = 0
        var centerCrossShaded = 0
        val yH1 = (topY + h * 0.10f).toInt()
        val yH2 = (topY + h * 0.36f).toInt()
        for (y in yH1..yH2) {
            val l = rowLeft[y]
            val r = rowRight[y]
            if (r - l >= 8) {
                val rimLum = rowMaxLum[y]
                for (x in (l + 2)..(r - 2)) {
                    if (lumGrid[y][x] <= rimLum - 24) {
                        innerShadedTop++
                        if (x in 13..18) centerCrossShaded++
                    }
                }
            }
        }

        // Вырез слона слева-сверху
        var bishopSlit = 0
        val yB1 = (topY + h * 0.22f).toInt()
        val yB2 = (topY + h * 0.48f).toInt()
        for (y in yB1..yB2) {
            val l = rowLeft[y]
            val r = rowRight[y]
            if (r - l >= 8) {
                for (x in (l + 1)..min(r - 2, l + 6)) {
                    if (!fg[y][x] && fg[y][min(N - 1, x + 3)]) bishopSlit++
                }
            }
        }

        val rawChar = when {
            row in 1..6 && ((topY >= 6 && fgCount <= 200 && wMid <= 14.2f) || (fgCount <= 175 && h <= 20)) -> 'p'
            jawAsym >= 11 && snoutLeft <= 8 -> 'n'
            wTop <= wMid - 2.2f && innerShadedTop <= 5 && (bishopSlit >= 2 || wVeryTop <= 7.0f) -> 'b'
            wTop >= 13.0f && abs(wTop - wMid) <= 2.6f && innerShadedTop >= 6 -> 'r'
            wVeryTop <= 8.5f && wMid >= 17.0f && centerCrossShaded >= 3 -> 'k'
            wTop >= 14.8f && wTop >= wWaist + 1.0f -> 'q'
            wTop < wMid - 1.5f -> 'b'
            else -> 'r'
        }

        val visualType = if (isWhite) rawChar.uppercaseChar() else rawChar
        val massScore = fgCount + h * 4 + wMid.toInt() * 3

        return CellFeatures(
            color = pieceColor,
            fgCount = fgCount,
            baseCount = baseCount,
            topY = topY,
            height = h,
            wVeryTop = wVeryTop,
            wTop = wTop,
            wMid = wMid,
            wWaist = wWaist,
            jawAsymmetry = jawAsym,
            snoutLeft = snoutLeft,
            innerShadedTop = innerShadedTop,
            centerCrossShaded = centerCrossShaded,
            bishopSlit = bishopSlit,
            visualType = visualType,
            massScore = massScore
        )
    }

    private fun isSolidArrowLine(r: Int, g: Int, b: Int): Boolean {
        // Фильтруем только непрозрачную ярко-зелёную стрелку и оранжевый пунктир (не трогая фигуры под подсветкой)
        if (r < 85 && g > 185 && g > r + 90 && g > b + 55) return true
        if (r > 180 && g in 100..215 && b < 85) return true
        return false
    }

    private fun countHomeMatches(cells: Array<Array<CellFeatures>>, whiteAtBottom: Boolean): Int {
        val topC = if (whiteAtBottom) 'B' else 'W'
        val botC = if (whiteAtBottom) 'W' else 'B'
        var count = 0
        for (c in 0..7) {
            if (cells[0][c].color == topC) count++
            if (cells[1][c].color == topC) count++
            if (cells[6][c].color == botC) count++
            if (cells[7][c].color == botC) count++
        }
        return count
    }

    private fun createStartingBoard(whiteAtBottom: Boolean): Array<CharArray> {
        return if (whiteAtBottom) {
            arrayOf(
                charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
                charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
                charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
            )
        } else {
            arrayOf(
                charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R'),
                charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
                charArrayOf('r', 'n', 'b', 'k', 'q', 'b', 'n', 'r')
            )
        }
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
