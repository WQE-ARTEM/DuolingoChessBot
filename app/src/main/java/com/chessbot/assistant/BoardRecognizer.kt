package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object BoardRecognizer {

    private var reusablePixels = IntArray(0)
    private val bgRList = IntArray(8)
    private val bgGList = IntArray(8)
    private val bgBList = IntArray(8)

    // Находит точный Y верхнего края доски по шахматному чередованию в центральных рядах (2..5)
    fun findBoardTopY(bitmap: Bitmap, expectedTopY: Int, isChessCom: Boolean): Int {
        val w = bitmap.width
        val h = bitmap.height
        val cellW = w / 8
        val minY = (h * 0.22f).toInt().coerceAtLeast(10)
        val maxY = (h * 0.48f).toInt().coerceAtMost(h - w - 10)

        var bestY = expectedTopY
        var bestScore = -999

        // Проверяем центральные ряды доски (ряды 2, 3, 4, 5 — там почти нет фигур и виден чистый шахматный узор)
        for (y in minY..maxY step 3) {
            var score = 0
            for (r in 2..5) {
                val sampleY = y + r * cellW + (cellW * 0.50f).toInt()
                if (sampleY >= h - 10) continue
                var prevLum = -1
                for (c in 0..7) {
                    val sampleX = c * cellW + (cellW * 0.14f).toInt()
                    val p = bitmap.getPixel(sampleX.coerceIn(0, w - 1), sampleY.coerceIn(0, h - 1))
                    val pr = Color.red(p); val pg = Color.green(p); val pb = Color.blue(p)
                    val lum = (pr * 299 + pg * 587 + pb * 114) / 1000

                    if (!isChessCom && (lum !in 16..92 || pr > pb + 12)) {
                        score -= 6
                    }
                    if (prevLum != -1) {
                        val diff = abs(lum - prevLum)
                        if (isChessCom) {
                            if (diff in 22..165) score += 4 else score -= 3
                        } else {
                            if (diff in 8..45 && lum in 16..92) score += 4 else score -= 4
                        }
                    }
                    prevLum = lum
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestY = y
            }
        }

        if (bestScore < 20) return expectedTopY

        // Доводка с точностью до 2 пикселей по верхней кромке 0-го ряда
        var exactY = bestY
        for (dy in -18..18 step 2) {
            val testY = (bestY + dy).coerceIn(minY, maxY)
            val p0 = bitmap.getPixel((cellW * 0.14f).toInt(), testY + 4)
            val p1 = bitmap.getPixel(cellW + (cellW * 0.14f).toInt(), testY + 4)
            val above0 = bitmap.getPixel((cellW * 0.14f).toInt(), (testY - 4).coerceAtLeast(0))
            val above1 = bitmap.getPixel(cellW + (cellW * 0.14f).toInt(), (testY - 4).coerceAtLeast(0))

            val l0 = (Color.red(p0) * 299 + Color.green(p0) * 587 + Color.blue(p0) * 114) / 1000
            val l1 = (Color.red(p1) * 299 + Color.green(p1) * 587 + Color.blue(p1) * 114) / 1000
            val la0 = (Color.red(above0) * 299 + Color.green(above0) * 587 + Color.blue(above0) * 114) / 1000
            val la1 = (Color.red(above1) * 299 + Color.green(above1) * 587 + Color.blue(above1) * 114) / 1000

            val insideAlternates = if (isChessCom) abs(l0 - l1) >= 24 else (abs(l0 - l1) in 8..45 && l0 in 16..92)
            val outsideFlat = abs(la0 - la1) <= 6
            if (insideAlternates && outsideFlat) {
                exactY = testY
                break
            }
        }
        return exactY
    }

    // Возвращает матрицу присутствия 'W' (белая), 'B' (чёрная), '.' (пусто) в ЭКРАННЫХ координатах [sr][sc]
    @Synchronized
    fun scanScreenPresence(
        bitmap: Bitmap,
        boardTopPx: Int,
        boardSizePx: Int,
        isChessCom: Boolean
    ): Array<CharArray>? {
        val top = boardTopPx.coerceIn(0, max(0, bitmap.height - 64))
        val size = min(boardSizePx, min(bitmap.width, bitmap.height - top))
        if (size < 120) return null

        val needed = size * size
        if (reusablePixels.size < needed) {
            reusablePixels = IntArray(needed)
        }
        bitmap.getPixels(reusablePixels, 0, size, 0, top, size, size)
        val cellSize = size / 8f

        val presence = Array(8) { CharArray(8) { '.' } }
        for (sr in 0..7) {
            for (sc in 0..7) {
                presence[sr][sc] = detectCellColor(
                    reusablePixels, size,
                    sc * cellSize, sr * cellSize, cellSize,
                    isChessCom
                )
            }
        }
        return presence
    }

    // Автоматически определяет, играет ли пользователь Белыми (внизу экрана) или Чёрными (внизу экрана)
    fun detectPlayerColorFromPresence(screenPresence: Array<CharArray>, currentWhiteAtBottom: Boolean): Boolean {
        var bottomW = 0; var bottomB = 0
        var topW = 0; var topB = 0
        for (sc in 0..7) {
            for (sr in 6..7) {
                if (screenPresence[sr][sc] == 'W') bottomW++
                else if (screenPresence[sr][sc] == 'B') bottomB++
            }
            for (sr in 0..1) {
                if (screenPresence[sr][sc] == 'W') topW++
                else if (screenPresence[sr][sc] == 'B') topB++
            }
        }
        val scoreWhiteBottom = bottomW + topB
        val scoreBlackBottom = bottomB + topW
        if (scoreWhiteBottom >= 18 && scoreWhiteBottom >= scoreBlackBottom + 8) return true
        if (scoreBlackBottom >= 18 && scoreBlackBottom >= scoreWhiteBottom + 8) return false
        return currentWhiteAtBottom
    }

    private val bgPoints = arrayOf(
        2 to 10, 2 to 15, 2 to 21,
        29 to 10, 29 to 15, 29 to 21,
        8 to 29, 23 to 29
    )

    // Безошибочно отличает Белую фигуру ('W'), Чёрную фигуру ('B') и Пустую клетку ('.')
    private fun detectCellColor(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        isChessCom: Boolean
    ): Char {
        val N = 32
        for (i in 0 until 8) {
            val (gx, gy) = bgPoints[i]
            val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val c = pixels[py * stride + px]
            bgRList[i] = Color.red(c)
            bgGList[i] = Color.green(c)
            bgBList[i] = Color.blue(c)
        }
        bgRList.sort(); bgGList.sort(); bgBList.sort()
        val bgR = bgRList[4]; val bgG = bgGList[4]; val bgB = bgBList[4]
        val bgLum = (bgR * 299 + bgG * 587 + bgB * 114) / 1000

        var fgTotal = 0
        var baseFg = 0
        var midFg = 0
        var whiteCoreVotes = 0
        var blackCoreVotes = 0

        for (gy in 5..27) {
            for (gx in 6..25) {
                // Игнорируем правый верхний уголок с мини-буквой
                if (gx >= 20 && gy <= 9) continue

                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                // Игнорируем ярко-зелёный цвет нашей стрелки и голубой цвет сетки
                if (g > 205 && g > r + 80 && b < 120) continue
                if (b > 205 && g > 190 && r < 45) continue

                val dr = r - bgR; val dg = g - bgG; val db = b - bgB
                val distSq = dr * dr + dg * dg + db * db
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                val sat = max(r, max(g, b)) - min(r, min(g, b))

                var isFg = false
                if (isChessCom) {
                    val isWhiteFill = r >= 228 && g >= 228 && b >= 218 && sat <= 24
                    val isDarkFill = lum <= 112 && sat <= 32
                    if ((isWhiteFill && distSq >= 450) || (isDarkFill && distSq >= 1500) || distSq >= 4400) {
                        isFg = true
                        if (isWhiteFill) whiteCoreVotes++
                        else if (lum in 35..112) blackCoreVotes++
                    }
                } else {
                    // Duolingo: белые фигуры имеют ярко-белое тело (lum >= 180), чёрные — тёмно-синее тело (lum 50..125) с голубым контуром
                    if (distSq >= 900 && lum >= bgLum + 16) {
                        isFg = true
                        if (lum >= 182 && r >= 155) {
                            whiteCoreVotes++
                        } else if (lum in 48..145) {
                            blackCoreVotes++
                        }
                    }
                }

                if (isFg) {
                    fgTotal++
                    if (gy in 10..19) midFg++
                    if (gy in 20..27) baseFg++
                }
            }
        }

        // У настоящей фигуры обязательно есть и широкое основание снизу (baseFg >= 14), и тело посередине (midFg >= 12)
        if (fgTotal < 38 || baseFg < 14 || midFg < 12) return '.'

        return if (isChessCom) {
            if (whiteCoreVotes >= 16 && whiteCoreVotes >= blackCoreVotes * 0.42f) 'W' else 'B'
        } else {
            if (whiteCoreVotes >= 14 && whiteCoreVotes >= fgTotal * 0.25f) 'W' else 'B'
        }
    }

    // Реконструкция фигур для головоломок или подключения в середине уже идущей партии
    @Synchronized
    fun reconstructBoardFromPresence(
        bitmap: Bitmap,
        boardTopPx: Int,
        boardSizePx: Int,
        isChessCom: Boolean,
        whiteAtBottom: Boolean,
        previousBoard: Array<CharArray>?
    ): Array<CharArray> {
        val screenPres = scanScreenPresence(bitmap, boardTopPx, boardSizePx, isChessCom)
            ?: return previousBoard ?: createInitialBoard()

        val bp = toBoardPresence(screenPres, whiteAtBottom)

        // 1. Проверяем старт новой партии (0 ходов или 1 ход Белых, когда мы играем Чёрными!)
        val openingBoard = tryDetectOpeningBoard(bp)
        if (openingBoard != null) return openingBoard

        val top = boardTopPx.coerceIn(0, max(0, bitmap.height - 64))
        val size = min(boardSizePx, min(bitmap.width, bitmap.height - top))
        val cellSize = size / 8f

        val result = Array(8) { CharArray(8) { '.' } }
        val stdInit = createInitialBoard()

        for (r in 0..7) {
            for (c in 0..7) {
                val pColor = bp[r][c]
                if (pColor == '.') {
                    result[r][c] = '.'
                    continue
                }
                val wantWhite = (pColor == 'W')

                // Если в памяти на этой клетке уже стояла фигура того же цвета — СОХРАНЯЕМ ЕЁ ТИП!
                val prevP = previousBoard?.get(r)?.get(c) ?: '.'
                if (prevP != '.' && prevP.isUpperCase() == wantWhite) {
                    result[r][c] = prevP
                    continue
                }

                // Если фигура стоит на своем родном стартовом месте (1, 2, 7, 8 горизонтали) — берем стартовый тип
                val initP = stdInit[r][c]
                if (initP != '.' && initP.isUpperCase() == wantWhite) {
                    result[r][c] = initP
                    continue
                }

                // Иначе оцениваем силуэт для новой фигуры в головоломке
                val sr = if (whiteAtBottom) r else 7 - r
                val sc = if (whiteAtBottom) c else 7 - c
                val guessed = classifyPuzzleSilhouette(reusablePixels, size, sc * cellSize, sr * cellSize, cellSize, r)
                result[r][c] = if (wantWhite) guessed.uppercaseChar() else guessed
            }
        }

        ensureValidKingsAndPawns(result)
        return result
    }

    fun toBoardPresence(screenPresence: Array<CharArray>, whiteAtBottom: Boolean): Array<CharArray> {
        val bp = Array(8) { CharArray(8) { '.' } }
        for (sr in 0..7) {
            for (sc in 0..7) {
                val r = if (whiteAtBottom) sr else 7 - sr
                val c = if (whiteAtBottom) sc else 7 - sc
                bp[r][c] = screenPresence[sr][sc]
            }
        }
        return bp
    }

    // Распознает как чистую начальную доску (0 ходов), так и начальную доску после 1-го хода Белых (когда мы играем Чёрными!)
    fun tryDetectOpeningBoard(bp: Array<CharArray>): Array<CharArray>? {
        var blackHomeCount = 0
        var whiteHomeCount = 0
        var midW = 0; var midB = 0
        var missingWhiteHome: Pair<Int, Int>? = null
        var arrivedWhiteMid: Pair<Int, Int>? = null
        var missingBlackHome: Pair<Int, Int>? = null
        var arrivedBlackMid: Pair<Int, Int>? = null

        for (c in 0..7) {
            for (r in 0..1) {
                if (bp[r][c] == 'B') blackHomeCount++
                else if (bp[r][c] == '.') missingBlackHome = r to c
            }
            for (r in 6..7) {
                if (bp[r][c] == 'W') whiteHomeCount++
                else if (bp[r][c] == '.') missingWhiteHome = r to c
            }
            for (r in 2..5) {
                if (bp[r][c] == 'W') { midW++; arrivedWhiteMid = r to c }
                else if (bp[r][c] == 'B') { midB++; arrivedBlackMid = r to c }
            }
        }

        // Случай А: 0 ходов (все 16 белых и 16 чёрных на старте)
        if (blackHomeCount == 16 && whiteHomeCount == 16 && midW == 0 && midB == 0) {
            return createInitialBoard()
        }

        // Случай Б: Белые сделали 1-й ход (например c2-c4, e2-e4, d2-d4, g1-f3), все 16 чёрных на месте
        if (blackHomeCount == 16 && whiteHomeCount == 15 && midW == 1 && midB == 0 &&
            missingWhiteHome != null && arrivedWhiteMid != null
        ) {
            val board = createInitialBoard()
            val (fr, fc) = missingWhiteHome
            val (tr, tc) = arrivedWhiteMid
            board[tr][tc] = board[fr][fc]
            board[fr][fc] = '.'
            return board
        }

        // Случай В: Белые сделали 1-й ход и Чёрные сделали 1-й ответный ход
        if (blackHomeCount == 15 && whiteHomeCount == 15 && midW == 1 && midB == 1 &&
            missingWhiteHome != null && arrivedWhiteMid != null &&
            missingBlackHome != null && arrivedBlackMid != null
        ) {
            val board = createInitialBoard()
            board[arrivedWhiteMid.first][arrivedWhiteMid.second] = board[missingWhiteHome.first][missingWhiteHome.second]
            board[missingWhiteHome.first][missingWhiteHome.second] = '.'
            board[arrivedBlackMid.first][arrivedBlackMid.second] = board[missingBlackHome.first][missingBlackHome.second]
            board[missingBlackHome.first][missingBlackHome.second] = '.'
            return board
        }

        return null
    }

    private fun classifyPuzzleSilhouette(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        boardRow: Int
    ): Char {
        val N = 32
        val bgR = Color.red(pixels[(cellY + 4).toInt().coerceIn(0, stride - 1) * stride + (cellX + 4).toInt().coerceIn(0, stride - 1)])
        var minGy = N; var maxGy = -1
        val widths = IntArray(N)

        for (gy in 3..28) {
            var l = N; var r = -1
            for (gx in 5..26) {
                if (gx >= 20 && gy <= 9) continue
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                if (abs(Color.red(c) - bgR) >= 34) {
                    if (gy < minGy) minGy = gy
                    if (gy > maxGy) maxGy = gy
                    if (gx < l) l = gx
                    if (gx > r) r = gx
                }
            }
            if (r >= l) widths[gy] = r - l + 1
        }

        val boxH = (maxGy - minGy + 1).coerceAtLeast(1)
        val relH = boxH / 24f
        var baseW = 1
        for (gy in max(3, maxGy - 5)..maxGy.coerceAtLeast(3)) if (widths[gy] > baseW) baseW = widths[gy]
        var headW = 0
        for (gy in minGy.coerceAtMost(28)..min(28, minGy + 6)) if (widths[gy] > headW) headW = widths[gy]
        val topRatio = headW.toFloat() / baseW.coerceAtLeast(1)

        val isBackRank = (boardRow == 0 || boardRow == 7)
        return when {
            !isBackRank && relH < 0.72f && topRatio < 0.70f -> 'p'
            relH >= 0.85f && topRatio >= 0.65f -> 'k'
            relH >= 0.78f && topRatio >= 0.75f -> 'q'
            topRatio >= 0.70f -> 'r'
            relH >= 0.74f && topRatio < 0.66f -> 'b'
            else -> 'n'
        }
    }

    private fun ensureValidKingsAndPawns(grid: Array<CharArray>) {
        for (r in intArrayOf(0, 7)) {
            for (c in 0..7) {
                if (grid[r][c] == 'P') grid[r][c] = 'R'
                if (grid[r][c] == 'p') grid[r][c] = 'r'
            }
        }
        for (whiteSide in booleanArrayOf(true, false)) {
            val kChar = if (whiteSide) 'K' else 'k'
            val kings = ArrayList<Pair<Int, Int>>()
            val anyPieces = ArrayList<Pair<Int, Int>>()
            for (r in 0..7) {
                for (c in 0..7) {
                    val p = grid[r][c]
                    if (p != '.' && p.isUpperCase() == whiteSide) {
                        anyPieces.add(r to c)
                        if (p == kChar) kings.add(r to c)
                    }
                }
            }
            if (kings.size > 1) {
                val homeR = if (whiteSide) 7 else 0
                val keep = kings.minByOrNull { abs(it.first - homeR) + abs(it.second - 4) }!!
                for (pos in kings) {
                    if (pos != keep) grid[pos.first][pos.second] = if (whiteSide) 'Q' else 'q'
                }
            } else if (kings.isEmpty() && anyPieces.isNotEmpty()) {
                val homeR = if (whiteSide) 7 else 0
                val best = anyPieces.minByOrNull { abs(it.first - homeR) + abs(it.second - 4) }!!
                grid[best.first][best.second] = kChar
            }
        }
    }

    fun createInitialBoard(): Array<CharArray> = arrayOf(
        charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
        charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
        charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
    )
}
