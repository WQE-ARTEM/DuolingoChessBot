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

    private data class CellVisual(
        val color: Char,        // '.' = пусто, 'W' = белая фигура, 'B' = чёрная фигура
        val fgCount: Int = 0,
        val baseCount: Int = 0,
        val topY: Int = 31,
        val height: Int = 0,
        val topWidth: Int = 0,
        val midWidth: Int = 0,
        val asymmetry: Int = 0,
        val massScore: Int = 0
    )

    fun isFrameStable(prev: Bitmap?, curr: Bitmap, topPx: Int, sizePx: Int): Boolean {
        if (prev == null || prev.width != curr.width || prev.height != curr.height) return false
        val safeTop = topPx.coerceIn(0, max(0, curr.height - 10))
        val safeSize = min(sizePx, curr.height - safeTop)
        if (safeSize < 80) return true

        var diff = 0
        var total = 0
        val step = max(14, safeSize / 20)
        var y = safeTop + step
        while (y < safeTop + safeSize - step) {
            var x = step
            while (x < curr.width - step) {
                val p1 = prev.getPixel(x, y)
                val p2 = curr.getPixel(x, y)
                if (abs(Color.red(p1) - Color.red(p2)) +
                    abs(Color.green(p1) - Color.green(p2)) +
                    abs(Color.blue(p1) - Color.blue(p2)) > 48
                ) diff++
                total++
                x += step
            }
            y += step
        }
        return total == 0 || (diff.toFloat() / total) < 0.05f
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

        val cellSize = size / 8f
        val visuals = Array(8) { r ->
            Array(8) { c ->
                analyzeCellVisual(bitmap, left + c * cellSize, top + r * cellSize, cellSize, r, c)
            }
        }

        // 1. Автоматически определяем, кто снизу (Белые или Чёрные)
        var whiteBottomVotes = 0
        var blackBottomVotes = 0
        var totalDetected = 0
        for (r in 0..7) {
            for (c in 0..7) {
                when (visuals[r][c].color) {
                    'W' -> {
                        totalDetected++
                        if (r >= 4) whiteBottomVotes += (if (r >= 6) 3 else 1)
                        else blackBottomVotes += (if (r <= 1) 3 else 1)
                    }
                    'B' -> {
                        totalDetected++
                        if (r <= 3) whiteBottomVotes += (if (r <= 1) 3 else 1)
                        else blackBottomVotes += (if (r >= 6) 3 else 1)
                    }
                }
            }
        }

        if (totalDetected < 2) {
            return ScanResult(emptyBoard, "", false, "Ожидание доски...", continueActive, whiteAtBottom)
        }

        val detectedWhiteBottom = if (whiteBottomVotes != blackBottomVotes) {
            whiteBottomVotes > blackBottomVotes
        } else {
            whiteAtBottom
        }

        // 2. Проверяем, нужно ли инициализировать/сбросить стартовую память
        val homeMatches = countHomeRankPieces(visuals, detectedWhiteBottom)
        val centerCount = (2..5).sumOf { r -> (0..7).count { c -> visuals[r][c].color != '.' } }

        if (!memoryInitialized || memoryOrientation != detectedWhiteBottom || (homeMatches >= 28 && centerCount <= 2)) {
            memoryBoard = createInitialBoard(detectedWhiteBottom)
            memoryOrientation = detectedWhiteBottom
            memoryInitialized = true
        }

        // 3. Строим актуальную доску по памяти + пулу ушедших со своих мест фигур
        val newBoard = Array(8) { CharArray(8) { '.' } }
        reconcileSide(
            targetColor = 'W',
            isWhiteBottom = detectedWhiteBottom,
            visuals = visuals,
            prevBoard = memoryBoard,
            outBoard = newBoard
        )
        reconcileSide(
            targetColor = 'B',
            isWhiteBottom = detectedWhiteBottom,
            visuals = visuals,
            prevBoard = memoryBoard,
            outBoard = newBoard
        )

        memoryBoard = Array(8) { r -> newBoard[r].clone() }

        val fen = buildFen(newBoard, detectedWhiteBottom)
        val sideLabel = if (detectedWhiteBottom) "Белые" else "Чёрные"

        return ScanResult(
            board = newBoard,
            fen = fen,
            isValid = true,
            statusNote = "Память OK ($sideLabel, $totalDetected фиг.)",
            continueButtonActive = continueActive,
            detectedWhiteAtBottom = detectedWhiteBottom
        )
    }

    private fun reconcileSide(
        targetColor: Char,
        isWhiteBottom: Boolean,
        visuals: Array<Array<CellVisual>>,
        prevBoard: Array<CharArray>,
        outBoard: Array<CharArray>
    ) {
        val isWhite = targetColor == 'W'
        val homeTemplate = createInitialBoard(isWhiteBottom)

        val homeBackRow = if (isWhite == isWhiteBottom) 7 else 0
        val homePawnRow = if (isWhite == isWhiteBottom) 6 else 1

        // Проверяем прямое перемещение ровно 1 фигуры с прошлого кадра
        val prevSquares = mutableListOf<Pair<Int, Int>>()
        val currSquares = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) {
            for (c in 0..7) {
                val prevP = prevBoard[r][c]
                if (prevP != '.' && prevP.isUpperCase() == isWhite) prevSquares.add(r to c)
                if (visuals[r][c].color == targetColor) currSquares.add(r to c)
            }
        }

        val vacatedFromPrev = prevSquares.filter { (r, c) -> visuals[r][c].color != targetColor }
        val arrivedFromPrev = currSquares.filter { (r, c) ->
            val p = prevBoard[r][c]
            p == '.' || p.isUpperCase() != isWhite
        }

        val directMovedPiece = if (vacatedFromPrev.size == 1 && arrivedFromPrev.size == 1) {
            val from = vacatedFromPrev[0]
            val to = arrivedFromPrev[0]
            to to prevBoard[from.first][from.second]
        } else null

        // Собираем пул фигур, которые покинули свои стартовые места
        val missingMajorPool = mutableListOf<Char>()
        var missingPawnCount = 0

        for (c in 0..7) {
            val expectedBack = homeTemplate[homeBackRow][c]
            if (visuals[homeBackRow][c].color == targetColor) {
                // Если фигура стоит на своем месте на заднем ряду (и не было рокировки на это поле)
                val prevAtBack = prevBoard[homeBackRow][c]
                outBoard[homeBackRow][c] = if (prevAtBack != '.' && prevAtBack.isUpperCase() == isWhite) {
                    prevAtBack
                } else {
                    expectedBack
                }
            } else {
                missingMajorPool.add(expectedBack)
            }

            val expectedPawn = homeTemplate[homePawnRow][c]
            if (visuals[homePawnRow][c].color == targetColor) {
                val prevAtPawn = prevBoard[homePawnRow][c]
                // Если в памяти туда уже пришла фигура, сохраняем её, иначе это стартовая пешка
                if (prevAtPawn != '.' && prevAtPawn.isUpperCase() == isWhite && prevAtPawn.lowercaseChar() != 'p' && visuals[homePawnRow][c].massScore >= 250) {
                    outBoard[homePawnRow][c] = prevAtPawn
                    missingPawnCount++
                } else {
                    outBoard[homePawnRow][c] = expectedPawn
                }
            } else {
                missingPawnCount++
            }
        }

        // Находим все клетки вне стартовых мест, где сейчас стоят фигуры нашего цвета
        val activeNonHome = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) {
            for (c in 0..7) {
                if (visuals[r][c].color == targetColor && outBoard[r][c] == '.') {
                    activeNonHome.add(r to c)
                }
            }
        }

        if (activeNonHome.isEmpty()) return

        val unassigned = mutableListOf<Pair<Int, Int>>()

        // А) Сначала применяем точный ход 1-в-1 с прошлого кадра, если он был
        for (pos in activeNonHome) {
            if (directMovedPiece != null && pos == directMovedPiece.first) {
                val ch = directMovedPiece.second
                outBoard[pos.first][pos.second] = ch
                if (ch.lowercaseChar() == 'p') {
                    missingPawnCount = max(0, missingPawnCount - 1)
                } else {
                    missingMajorPool.remove(ch)
                }
            } else {
                unassigned.add(pos)
            }
        }

        // Б) Сохраняем фигуры, которые уже стояли на этих же клетках в прошлом кадре и входят в пул
        val stillUnassigned = mutableListOf<Pair<Int, Int>>()
        for (pos in unassigned) {
            val prevCh = prevBoard[pos.first][pos.second]
            if (prevCh != '.' && prevCh.isUpperCase() == isWhite) {
                if (prevCh.lowercaseChar() == 'p' && missingPawnCount > 0 && visuals[pos.first][pos.second].massScore < 260) {
                    outBoard[pos.first][pos.second] = prevCh
                    missingPawnCount--
                } else if (missingMajorPool.contains(prevCh)) {
                    outBoard[pos.first][pos.second] = prevCh
                    missingMajorPool.remove(prevCh)
                } else {
                    stillUnassigned.add(pos)
                }
            } else {
                stillUnassigned.add(pos)
            }
        }

        if (stillUnassigned.isEmpty()) return

        // В) Оставшиеся клетки сортируем по размеру (massScore от большего к меньшему):
        // Самые крупные получают сходившие старшие фигуры из missingMajorPool, а меньшие — пешки!
        val sortedBySize = stillUnassigned.sortedByDescending { (r, c) -> visuals[r][c].massScore }
        val pawnChar = if (isWhite) 'P' else 'p'

        for (pos in sortedBySize) {
            val v = visuals[pos.first][pos.second]
            val looksLikeMajor = v.massScore >= 235 || missingPawnCount <= 0
            if (missingMajorPool.isNotEmpty() && looksLikeMajor) {
                val picked = pickBestMajorFromPool(missingMajorPool, v)
                outBoard[pos.first][pos.second] = picked
                missingMajorPool.remove(picked)
            } else if (missingPawnCount > 0) {
                outBoard[pos.first][pos.second] = pawnChar
                missingPawnCount--
            } else if (missingMajorPool.isNotEmpty()) {
                val picked = pickBestMajorFromPool(missingMajorPool, v)
                outBoard[pos.first][pos.second] = picked
                missingMajorPool.remove(picked)
            } else {
                outBoard[pos.first][pos.second] = classifyByVisualOnly(v, isWhite)
            }
        }

        // Гарантируем наличие короля на доске
        val kingChar = if (isWhite) 'K' else 'k'
        val hasKing = (0..7).any { r -> (0..7).any { c -> outBoard[r][c] == kingChar } }
        if (!hasKing && currSquares.isNotEmpty()) {
            val bestKingSq = currSquares.maxByOrNull { (r, c) -> visuals[r][c].massScore }!!
            outBoard[bestKingSq.first][bestKingSq.second] = kingChar
        }
    }

    private fun pickBestMajorFromPool(pool: List<Char>, v: CellVisual): Char {
        if (pool.size == 1) return pool[0]
        // Если в пуле есть конь и фигура явно асимметрична
        if (v.asymmetry >= 10) {
            pool.firstOrNull { it.lowercaseChar() == 'n' }?.let { return it }
        }
        // Если узкая верхушка — слон
        if (v.topWidth <= v.midWidth - 3) {
            pool.firstOrNull { it.lowercaseChar() == 'b' }?.let { return it }
        }
        // Если очень крупная с широким верхом — ферзь или ладья
        if (v.topWidth >= 14) {
            pool.firstOrNull { it.lowercaseChar() == 'q' }?.let { return it }
            pool.firstOrNull { it.lowercaseChar() == 'r' }?.let { return it }
        }
        return pool[0]
    }

    private fun classifyByVisualOnly(v: CellVisual, isWhite: Boolean): Char {
        val ch = when {
            v.massScore < 230 -> 'p'
            v.asymmetry >= 11 -> 'n'
            v.topWidth <= v.midWidth - 3 -> 'b'
            v.topWidth >= 15 && v.height >= 21 -> 'q'
            else -> 'r'
        }
        return if (isWhite) ch.uppercaseChar() else ch
    }

    private fun analyzeCellVisual(
        bmp: Bitmap,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        row: Int,
        col: Int
    ): CellVisual {
        val N = 32
        // Измеряем яркость фона в углах клетки (где никогда нет ни фигуры, ни кружка-подсказки)
        var bgSum = 0
        var bgCnt = 0
        val corners = arrayOf(2 to 29, 3 to 29, 28 to 29, 29 to 29, 28 to 2, 29 to 2, 2 to 25, 29 to 25)
        for ((gx, gy) in corners) {
            val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, bmp.width - 1)
            val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(px, py)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            if (!isArrowOverlay(r, g, b)) {
                bgSum += (r * 299 + g * 587 + b * 114) / 1000
                bgCnt++
            }
        }
        val defaultBg = if ((row + col) % 2 == 0) 53 else 33
        val bgLum = if (bgCnt > 0) (bgSum / bgCnt).coerceIn(22, 95) else defaultBg

        var fgCount = 0
        var brightWhiteCount = 0
        var baseCount = 0
        var bodyCount = 0
        var topY = 31
        var bottomY = 0
        val rowLeft = IntArray(N) { 31 }
        val rowRight = IntArray(N) { 0 }
        var leftMass = 0
        var rightMass = 0

        for (gy in 2 until N - 2) {
            for (gx in 3 until N - 3) {
                // Игнорируем левый верхний угол с текстовой меткой оверлея (wP, bN и т.д.)
                if (gx <= 10 && gy <= 7) continue

                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, bmp.width - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(px, py)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                if (isArrowOverlay(r, g, b)) continue

                val lum = (r * 299 + g * 587 + b * 114) / 1000

                // Пиксель принадлежит фигуре только если он заметно светлее фона самой клетки (отсекает светлые поля!)
                if (lum >= max(74, bgLum + 24) && b >= r - 20) {
                    fgCount++
                    if (lum >= 172) brightWhiteCount++
                    if (gy in 20..27) baseCount++
                    if (gy in 9..19) bodyCount++
                    if (gy < topY) topY = gy
                    if (gy > bottomY) bottomY = gy
                    if (gx < rowLeft[gy]) rowLeft[gy] = gx
                    if (gx > rowRight[gy]) rowRight[gy] = gx
                    if (gy in 13..21) {
                        if (gx < 16) leftMass++ else rightMass++
                    }
                }
            }
        }

        // Отсекаем пустые поля и кружки-подсказки в центре (у кружков baseCount == 0)
        if (fgCount < 48 || baseCount < 14 || bodyCount < 18 || bottomY <= topY + 5) {
            return CellVisual('.')
        }

        val pieceColor = if (brightWhiteCount >= fgCount * 0.38f) 'W' else 'B'
        val h = bottomY - topY + 1
        val topW = ((topY + 2)..min(bottomY, topY + 6)).maxOfOrNull { y ->
            if (rowRight[y] >= rowLeft[y]) rowRight[y] - rowLeft[y] + 1 else 0
        } ?: 0
        val midW = ((topY + h / 3)..min(bottomY, topY + (2 * h) / 3)).maxOfOrNull { y ->
            if (rowRight[y] >= rowLeft[y]) rowRight[y] - rowLeft[y] + 1 else 0
        } ?: 0
        val asym = rightMass - leftMass
        val massScore = fgCount + h * 5 + topW * 3

        return CellVisual(
            color = pieceColor,
            fgCount = fgCount,
            baseCount = baseCount,
            topY = topY,
            height = h,
            topWidth = topW,
            midWidth = midW,
            asymmetry = asym,
            massScore = massScore
        )
    }

    private fun isArrowOverlay(r: Int, g: Int, b: Int): Boolean {
        // Зелёная стрелка оверлея на тёмном фоне (не задевает белые фигуры)
        if (g > r + 42 && g > b + 25 && r < 135) return true
        // Оранжевый пунктир ponder-хода
        if (r > 175 && g in 100..215 && b < 85) return true
        return false
    }

    private fun countHomeRankPieces(visuals: Array<Array<CellVisual>>, whiteAtBottom: Boolean): Int {
        val topColor = if (whiteAtBottom) 'B' else 'W'
        val botColor = if (whiteAtBottom) 'W' else 'B'
        var matches = 0
        for (c in 0..7) {
            if (visuals[0][c].color == topColor) matches++
            if (visuals[1][c].color == topColor) matches++
            if (visuals[6][c].color == botColor) matches++
            if (visuals[7][c].color == botColor) matches++
        }
        return matches
    }

    private fun createInitialBoard(whiteAtBottom: Boolean): Array<CharArray> {
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
            // Когда чёрные снизу, колонки идут от h к a: король на col 3 (вертикаль e), ферзь на col 4 (вертикаль d)
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
                total++; x += 14
            }
            y += 9
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
