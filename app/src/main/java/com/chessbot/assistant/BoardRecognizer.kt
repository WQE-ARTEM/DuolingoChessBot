package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    private var reusablePixels = IntArray(0)
    private val bgRList = IntArray(6)
    private val bgGList = IntArray(6)
    private val bgBList = IntArray(6)

    // Ищет точный верхний край доски ТОЛЬКО в реальной зоне доски (0.310 .. 0.372 высоты экрана)
    // Никогда не запрыгивает на лицо Оскара или облачко диалога!
    fun findBoardTopY(bitmap: Bitmap, expectedTopY: Int, isChessCom: Boolean): Int {
        val w = bitmap.width
        val h = bitmap.height
        val cellW = w / 8
        // Строгие границы: доска физически всегда начинается между 31.0% и 37.2% высоты экрана
        val minY = (h * 0.310f).toInt().coerceAtLeast(10)
        val maxY = (h * 0.372f).toInt().coerceAtMost(h - w - 10)

        var bestY = expectedTopY.coerceIn(minY, maxY)
        var bestScore = -9999

        for (y in minY..maxY step 2) {
            var score = 0
            // Проверяем шахматный паттерн: в каждом ряду (2, 3, 4, 5) четные клетки светлее нечетных на четных рядах и наоборот
            for (r in 2..5) {
                val sy = y + r * cellW + (cellW * 0.42f).toInt()
                if (sy >= h - 8) continue
                var lightSum = 0
                var darkSum = 0
                var validRow = true

                for (c in 0..7) {
                    val sx = c * cellW + (cellW * 0.85f).toInt()
                    val p = bitmap.getPixel(sx.coerceIn(0, w - 1), sy)
                    val pr = Color.red(p); val pg = Color.green(p); val pb = Color.blue(p)
                    val lum = (pr * 299 + pg * 587 + pb * 114) / 1000

                    if (!isChessCom && (lum !in 16..88 || pr > pb + 12)) {
                        validRow = false
                        break
                    }
                    if ((r + c) % 2 == 0) lightSum += lum else darkSum += lum
                }

                if (!validRow) {
                    score -= 30
                } else {
                    val avgDiff = (lightSum - darkSum) / 4
                    if (isChessCom && avgDiff in 20..160) score += 25
                    else if (!isChessCom && avgDiff in 8..42) score += 25
                    else score -= 15
                }
            }

            // Дополнительный бонус за чёткую верхнюю границу над 0-м рядом и нижнюю границу под 7-м рядом
            val aboveY = (y - 6).coerceAtLeast(0)
            val belowY = (y + w + 6).coerceAtMost(h - 1)
            val pAbove0 = bitmap.getPixel((cellW * 0.5f).toInt(), aboveY)
            val pAbove1 = bitmap.getPixel(cellW + (cellW * 0.5f).toInt(), aboveY)
            val pBelow0 = bitmap.getPixel((cellW * 0.5f).toInt(), belowY)
            val pBelow1 = bitmap.getPixel(cellW + (cellW * 0.5f).toInt(), belowY)

            val diffAbove = abs(Color.red(pAbove0) - Color.red(pAbove1)) + abs(Color.blue(pAbove0) - Color.blue(pAbove1))
            val diffBelow = abs(Color.red(pBelow0) - Color.red(pBelow1)) + abs(Color.blue(pBelow0) - Color.blue(pBelow1))

            if (diffAbove <= 8) score += 12
            if (diffBelow <= 8) score += 18

            if (score > bestScore) {
                bestScore = score
                bestY = y
            }
        }

        return if (bestScore >= 50) bestY else expectedTopY.coerceIn(minY, maxY)
    }

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

    // Точки фона по краям в нижней половине клетки (вдали от левого верхнего бейджа и от фигуры в центре)
    private val bgPoints = arrayOf(
        2 to 15, 2 to 23,
        29 to 15, 29 to 23,
        6 to 29, 25 to 29
    )

    // Сканирует ТОЛЬКО нижнюю центральную часть клетки (gx 8..23, gy 13..27), где находится массивное основание фигуры!
    // Левый верхний угол с бейджем (gx 0..11, gy 0..9) никогда не попадает в скан!
    private fun detectCellColor(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float,
        isChessCom: Boolean
    ): Char {
        val N = 32
        for (i in 0 until 6) {
            val (gx, gy) = bgPoints[i]
            val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
            val c = pixels[py * stride + px]
            bgRList[i] = Color.red(c)
            bgGList[i] = Color.green(c)
            bgBList[i] = Color.blue(c)
        }
        bgRList.sort(); bgGList.sort(); bgBList.sort()
        val bgR = bgRList[3]; val bgG = bgGList[3]; val bgB = bgBList[3]
        val bgLum = (bgR * 299 + bgG * 587 + bgB * 114) / 1000

        var fgTotal = 0
        var baseFg = 0
        var whiteCoreVotes = 0
        var blackCoreVotes = 0

        for (gy in 13..27) {
            for (gx in 8..23) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                // Игнорируем ярко-зелёный цвет нашей стрелки
                if (g > 200 && g > r + 80 && b < 130) continue

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
                    if (distSq >= 950 && lum >= bgLum + 18) {
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
                    if (gy >= 20) baseFg++
                }
            }
        }

        if (fgTotal < 28 || baseFg < 12) return '.'

        return if (isChessCom) {
            if (whiteCoreVotes >= 12 && whiteCoreVotes >= blackCoreVotes * 0.42f) 'W' else 'B'
        } else {
            if (whiteCoreVotes >= 10 && whiteCoreVotes >= fgTotal * 0.25f) 'W' else 'B'
        }
    }

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

                val prevP = previousBoard?.get(r)?.get(c) ?: '.'
                if (prevP != '.' && prevP.isUpperCase() == wantWhite) {
                    result[r][c] = prevP
                    continue
                }

                val initP = stdInit[r][c]
                if (initP != '.' && initP.isUpperCase() == wantWhite) {
                    result[r][c] = initP
                    continue
                }

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

        if (blackHomeCount == 16 && whiteHomeCount == 16 && midW == 0 && midB == 0) {
            return createInitialBoard()
        }

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
        val bgR = Color.red(pixels[(cellY + (24 * cellSize / N)).toInt().coerceIn(0, stride - 1) * stride + (cellX + 4).toInt().coerceIn(0, stride - 1)])
        var minGy = N; var maxGy = -1
        val widths = IntArray(N)

        for (gy in 3..28) {
            var l = N; var r = -1
            for (gx in 6..26) {
                if (gx <= 11 && gy <= 9) continue // пропускаем левый верхний бейдж
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
