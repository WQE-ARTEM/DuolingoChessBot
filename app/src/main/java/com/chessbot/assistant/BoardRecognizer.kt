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
                presence[sr][sc] = if (isChessCom) {
                    detectChessComCell(reusablePixels, size, sc * cellSize, sr * cellSize, cellSize)
                } else {
                    detectDuolingoCell(reusablePixels, size, sc * cellSize, sr * cellSize, cellSize)
                }
            }
        }
        return presence
    }

    // В Duolingo яркость пустых клеток (25..58), чёрных фигур (68..165) и белых фигур (180..255) не пересекается!
    private fun detectDuolingoCell(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float
    ): Char {
        val N = 32
        var whitePixels = 0   // lum >= 180 (ярко-белая заливка белых фигур)
        var blackOutline = 0  // lum in 92..172 (голубой контур чёрных фигур)
        var blackBody = 0     // lum in 64..91 (синее тело чёрных фигур)

        // Сканируем центральную зону клетки (gx 7..25, gy 9..26), не задевая левый верхний бейдж и края клетки
        for (gy in 9..26) {
            for (gx in 7..25) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                // Пропускаем ярко-зелёную стрелку подсказки
                if (g > 200 && g > r + 75 && b < 130) continue
                // Пропускаем жёлтую рамку бейджа, если вдруг задели
                if (r > 220 && g > 170 && b < 40) continue

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                when {
                    lum >= 180 && r >= 150 -> whitePixels++
                    lum in 92..172 && b >= r -> blackOutline++
                    lum in 64..91 && b >= r -> blackBody++
                }
            }
        }

        // Если в центре клетки >= 22 ярко-белых пикселей — это 100% Белая фигура
        if (whitePixels >= 22) return 'W'

        // Если есть контур чёрной фигуры (blackOutline >= 8) и её тело (blackOutline + blackBody >= 26) — это 100% Чёрная фигура
        if (blackOutline >= 8 && (blackOutline + blackBody) >= 26) return 'B'

        return '.'
    }

    private val bgPoints = arrayOf(
        2 to 15, 2 to 23,
        29 to 15, 29 to 23,
        6 to 29, 25 to 29
    )

    private fun detectChessComCell(
        pixels: IntArray,
        stride: Int,
        cellX: Float,
        cellY: Float,
        cellSize: Float
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

        var fgTotal = 0
        var baseFg = 0
        var whiteVotes = 0
        var blackVotes = 0

        for (gy in 11..27) {
            for (gx in 7..24) {
                val px = (cellX + (gx + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val py = (cellY + (gy + 0.5f) * cellSize / N).toInt().coerceIn(0, stride - 1)
                val c = pixels[py * stride + px]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

                if (g > 200 && g > r + 80 && b < 130) continue

                val dr = r - bgR; val dg = g - bgG; val db = b - bgB
                val distSq = dr * dr + dg * dg + db * db
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                val sat = max(r, max(g, b)) - min(r, min(g, b))

                val isWhiteFill = r >= 228 && g >= 228 && b >= 218 && sat <= 24
                val isDarkFill = lum <= 112 && sat <= 32
                if ((isWhiteFill && distSq >= 450) || (isDarkFill && distSq >= 1500) || distSq >= 4400) {
                    fgTotal++
                    if (gy >= 20) baseFg++
                    if (isWhiteFill) whiteVotes++
                    else if (lum in 35..112) blackVotes++
                }
            }
        }

        if (fgTotal < 28 || baseFg < 12) return '.'
        return if (whiteVotes >= 14 && whiteVotes >= blackVotes * 0.42f) 'W' else 'B'
    }

    // Автоматически определяет, за кого играет пользователь: Белые (true) или Чёрные (false)
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
        if (scoreWhiteBottom >= 16 && scoreWhiteBottom >= scoreBlackBottom + 6) return true
        if (scoreBlackBottom >= 16 && scoreBlackBottom >= scoreWhiteBottom + 6) return false
        return currentWhiteAtBottom
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

    // Распознаёт старт новой партии (0 ходов, 1 ход Белых как f2-f3 на скрине, или по 1 ходу с каждой стороны)
    fun tryDetectOpeningBoard(bp: Array<CharArray>): Array<CharArray>? {
        var blackHomeCount = 0
        var whiteHomeCount = 0
        var midW = 0; var midB = 0
        val missingWhiteHome = ArrayList<Pair<Int, Int>>()
        val arrivedWhiteMid = ArrayList<Pair<Int, Int>>()
        val missingBlackHome = ArrayList<Pair<Int, Int>>()
        val arrivedBlackMid = ArrayList<Pair<Int, Int>>()

        for (c in 0..7) {
            for (r in 0..1) {
                if (bp[r][c] == 'B') blackHomeCount++
                else if (bp[r][c] == '.') missingBlackHome.add(r to c)
            }
            for (r in 6..7) {
                if (bp[r][c] == 'W') whiteHomeCount++
                else if (bp[r][c] == '.') missingWhiteHome.add(r to c)
            }
            for (r in 2..5) {
                if (bp[r][c] == 'W') { midW++; arrivedWhiteMid.add(r to c) }
                else if (bp[r][c] == 'B') { midB++; arrivedBlackMid.add(r to c) }
            }
        }

        // 0 ходов (чистый старт за Белых)
        if (blackHomeCount == 16 && whiteHomeCount == 16 && midW == 0 && midB == 0) {
            return createInitialBoard()
        }

        // 1 ход Белых (старт за Чёрных — например Белые сходили f2-f3, e2-e4, d2-d4, g1-f3)
        if (blackHomeCount == 16 && whiteHomeCount == 15 && midW == 1 && midB == 0 &&
            missingWhiteHome.size == 1 && arrivedWhiteMid.size == 1
        ) {
            val board = createInitialBoard()
            val (fr, fc) = missingWhiteHome[0]
            val (tr, tc) = arrivedWhiteMid[0]
            board[tr][tc] = board[fr][fc]
            board[fr][fc] = '.'
            return board
        }

        // 1 ход Белых + 1 ход Чёрных
        if (blackHomeCount == 15 && whiteHomeCount == 15 && midW == 1 && midB == 1 &&
            missingWhiteHome.size == 1 && arrivedWhiteMid.size == 1 &&
            missingBlackHome.size == 1 && arrivedBlackMid.size == 1
        ) {
            val board = createInitialBoard()
            board[arrivedWhiteMid[0].first][arrivedWhiteMid[0].second] = board[missingWhiteHome[0].first][missingWhiteHome[0].second]
            board[missingWhiteHome[0].first][missingWhiteHome[0].second] = '.'
            board[arrivedBlackMid[0].first][arrivedBlackMid[0].second] = board[missingBlackHome[0].first][missingBlackHome[0].second]
            board[missingBlackHome[0].first][missingBlackHome[0].second] = '.'
            return board
        }

        return null
    }

    // Умная синхронизация без потери типов фигур при нажатии "Скан" в середине партии
    fun syncBoardWithPresence(
        bp: Array<CharArray>,
        previousBoard: Array<CharArray>
    ): Array<CharArray> {
        val opening = tryDetectOpeningBoard(bp)
        if (opening != null) return opening

        val result = Array(8) { CharArray(8) { '.' } }
        val stdInit = createInitialBoard()

        for (r in 0..7) {
            for (c in 0..7) {
                val color = bp[r][c]
                if (color == '.') {
                    result[r][c] = '.'
                    continue
                }
                val wantWhite = (color == 'W')
                val prev = previousBoard[r][c]
                if (prev != '.' && prev.isUpperCase() == wantWhite) {
                    result[r][c] = prev
                    continue
                }
                val initP = stdInit[r][c]
                if (initP != '.' && initP.isUpperCase() == wantWhite) {
                    result[r][c] = initP
                    continue
                }
                result[r][c] = if (wantWhite) 'P' else 'p'
            }
        }
        return result
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
