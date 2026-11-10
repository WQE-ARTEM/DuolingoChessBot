package com.chessbot.assistant

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class EngineAnalysis(
    val bestMoveUci: String,
    val ponderMoveUci: String?,
    val evalScoreCp: Int,
    val engineName: String
)

class ChessEngine(private val context: Context) {

    private var sfProcess: Process? = null
    private var sfReader: BufferedReader? = null
    private var sfWriter: BufferedWriter? = null
    private var stockfishReady = false

    init {
        initStockfish()
    }

    private fun initStockfish() {
        try {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val sfFile = File(nativeDir, "libstockfish.so")
            if (sfFile.exists()) {
                sfFile.setExecutable(true)
                val pb = ProcessBuilder(sfFile.absolutePath)
                pb.redirectErrorStream(true)
                sfProcess = pb.start()
                sfReader = BufferedReader(InputStreamReader(sfProcess!!.inputStream))
                sfWriter = BufferedWriter(OutputStreamWriter(sfProcess!!.outputStream))

                sendUciCommand("uci")
                sendUciCommand("setoption name Threads value 4")
                sendUciCommand("setoption name Hash value 64")
                sendUciCommand("setoption name Skill Level value 20")
                sendUciCommand("setoption name Contempt value 24")
                sendUciCommand("isready")

                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 2000) {
                    val line = sfReader?.readLine() ?: break
                    if (line.contains("readyok")) {
                        stockfishReady = true
                        break
                    }
                }
            }
        } catch (e: Exception) {
            stockfishReady = false
        }
    }

    private fun sendUciCommand(cmd: String) {
        sfWriter?.write("$cmd\n")
        sfWriter?.flush()
    }

    fun analyzePosition(fen: String, moveTimeMs: Int = 520): EngineAnalysis {
        if (!stockfishReady) initStockfish()
        if (stockfishReady) {
            val sfResult = analyzeWithStockfish(fen, moveTimeMs)
            if (sfResult != null) return sfResult
        }
        return GrandmasterKotlinEngine.findBestMove(fen)
    }

    private fun analyzeWithStockfish(fen: String, moveTimeMs: Int): EngineAnalysis? {
        return try {
            sendUciCommand("position fen $fen")
            sendUciCommand("go movetime $moveTimeMs")

            var bestMove = ""
            var ponderMove: String? = null
            var scoreCp = 0
            val whiteTurn = fen.contains(" w ")

            val deadline = System.currentTimeMillis() + moveTimeMs + 900
            while (System.currentTimeMillis() < deadline) {
                val line = sfReader?.readLine() ?: break
                if (line.startsWith("info") && line.contains("score ")) {
                    val parts = line.split(" ")
                    val idx = parts.indexOf("score")
                    if (idx != -1 && idx + 2 < parts.size) {
                        val type = parts[idx + 1]
                        val rawVal = parts[idx + 2].toIntOrNull() ?: 0
                        scoreCp = if (type == "mate") {
                            if (rawVal > 0) 9900 else -9900
                        } else {
                            rawVal
                        }
                        if (!whiteTurn) scoreCp = -scoreCp
                    }
                } else if (line.startsWith("bestmove")) {
                    val parts = line.split(" ")
                    if (parts.size >= 2) bestMove = parts[1]
                    val pIdx = parts.indexOf("ponder")
                    if (pIdx != -1 && pIdx + 1 < parts.size) {
                        ponderMove = parts[pIdx + 1]
                    }
                    break
                }
            }
            if (bestMove.length >= 4 && bestMove != "(none)") {
                EngineAnalysis(bestMove, ponderMove, scoreCp, "Stockfish GM (3480)")
            } else null
        } catch (e: Exception) {
            stockfishReady = false
            null
        }
    }

    fun close() {
        try {
            sendUciCommand("quit")
            sfProcess?.destroy()
        } catch (_: Exception) {}
    }
}

/**
 * Усиленный движок на Kotlin с дебютной книгой Магнуса Карлсена, таблицами PeSTO,
 * Alpha-Beta + Quiescence Search (форсированный расчет взятий) и MVV-LVA.
 */
object GrandmasterKotlinEngine {

    // Дебютная книга гроссмейстерских ответов для мгновенной идеальной игры в начале партии
    private val GM_OPENING_BOOK = mapOf(
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w" to ("e2e4" to "e7e5"),
        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b" to ("c7c5" to "g1f3"),
        "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b" to ("g8f6" to "c2c4"),
        "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w" to ("g1f3" to "b8c6"),
        "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w" to ("g1f3" to "d7d6"),
        "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w" to ("f1b5" to "a7a6"),
        "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPP1PPPP/RNBQKBNR w" to ("c2c4" to "e7e6")
    )

    private val PIECE_VALUES = mapOf(
        'P' to 100, 'N' to 320, 'B' to 335, 'R' to 500, 'Q' to 950, 'K' to 20000,
        'p' to -100, 'n' to -320, 'b' to -335, 'r' to -500, 'q' to -950, 'k' to -20000
    )

    // Таблицы ценности полей (Piece-Square Tables) со стороны белых (row 0 = 8-я горизонталь, row 7 = 1-я)
    private val PAWN_PST = arrayOf(
        intArrayOf( 0,  0,  0,  0,  0,  0,  0,  0),
        intArrayOf(50, 50, 50, 50, 50, 50, 50, 50),
        intArrayOf(10, 10, 20, 30, 30, 20, 10, 10),
        intArrayOf( 5,  5, 10, 27, 27, 10,  5,  5),
        intArrayOf( 0,  0,  0, 24, 24,  0,  0,  0),
        intArrayOf( 5, -5,-10,  0,  0,-10, -5,  5),
        intArrayOf( 5, 10, 10,-20,-20, 10, 10,  5),
        intArrayOf( 0,  0,  0,  0,  0,  0,  0,  0)
    )

    private val KNIGHT_PST = arrayOf(
        intArrayOf(-50,-40,-30,-30,-30,-30,-40,-50),
        intArrayOf(-40,-20,  0,  0,  0,  0,-20,-40),
        intArrayOf(-30,  0, 15, 20, 20, 15,  0,-30),
        intArrayOf(-30,  5, 20, 25, 25, 20,  5,-30),
        intArrayOf(-30,  0, 20, 25, 25, 20,  0,-30),
        intArrayOf(-30,  5, 15, 20, 20, 15,  5,-30),
        intArrayOf(-40,-20,  0,  5,  5,  0,-20,-40),
        intArrayOf(-50,-40,-30,-30,-30,-30,-40,-50)
    )

    private val BISHOP_PST = arrayOf(
        intArrayOf(-20,-10,-10,-10,-10,-10,-10,-20),
        intArrayOf(-10,  0,  0,  0,  0,  0,  0,-10),
        intArrayOf(-10,  0, 10, 15, 15, 10,  0,-10),
        intArrayOf(-10,  5, 10, 15, 15, 10,  5,-10),
        intArrayOf(-10,  0, 15, 15, 15, 15,  0,-10),
        intArrayOf(-10, 10, 10, 10, 10, 10, 10,-10),
        intArrayOf(-10,  5,  0,  0,  0,  0,  5,-10),
        intArrayOf(-20,-10,-10,-10,-10,-10,-10,-20)
    )

    data class Move(
        val r1: Int,
        val c1: Int,
        val r2: Int,
        val c2: Int,
        val promo: Char? = null,
        val isCastling: Boolean = false,
        val isCapture: Boolean = false
    ) {
        fun toUci(): String {
            val f1 = ('a' + c1)
            val rk1 = ('8' - r1)
            val f2 = ('a' + c2)
            val rk2 = ('8' - r2)
            return "$f1$rk1$f2$rk2${promo ?: ""}"
        }
    }

    fun findBestMove(fen: String): EngineAnalysis {
        val parts = fen.split(" ")
        val boardKey = "${parts[0]} ${parts.getOrNull(1) ?: "w"}"
        GM_OPENING_BOOK[boardKey]?.let { (best, ponder) ->
            return EngineAnalysis(best, ponder, 35, "GM Book + PeSTO")
        }

        val board = parseFenBoard(parts[0])
        val whiteToMove = parts.getOrNull(1) != "b"
        val castling = parts.getOrNull(2) ?: "KQkq"

        val moves = generateLegalMoves(board, whiteToMove, castling)
        if (moves.isEmpty()) {
            return EngineAnalysis("e1g1", null, 0, "GM PeSTO")
        }

        var bestMove = moves[0]
        var bestScore = if (whiteToMove) -999999 else 999999

        for (m in moves) {
            val nextBoard = applyMove(board, m)
            // Если ход ставит мат — выбираем его мгновенно!
            val oppLegal = generateLegalMoves(nextBoard, !whiteToMove, "-")
            if (oppLegal.isEmpty() && isKingInCheck(nextBoard, !whiteToMove)) {
                return EngineAnalysis(m.toUci(), null, if (whiteToMove) 9900 else -9900, "GM PeSTO (MATE)")
            }

            val score = alphaBeta(nextBoard, 3, -999999, 999999, !whiteToMove)
            val castlingBonus = if (m.isCastling) (if (whiteToMove) 75 else -75) else 0
            val totalScore = score + castlingBonus

            if (whiteToMove && totalScore > bestScore) {
                bestScore = totalScore
                bestMove = m
            } else if (!whiteToMove && totalScore < bestScore) {
                bestScore = totalScore
                bestMove = m
            }
        }

        val afterBest = applyMove(board, bestMove)
        val oppMoves = generateLegalMoves(afterBest, !whiteToMove, "-")
        val bestReply = oppMoves.maxByOrNull { m ->
            val s = evaluatePosition(applyMove(afterBest, m))
            if (!whiteToMove) s else -s
        }

        return EngineAnalysis(
            bestMoveUci = bestMove.toUci(),
            ponderMoveUci = bestReply?.toUci(),
            evalScoreCp = bestScore,
            engineName = "GM PeSTO"
        )
    }

    private fun alphaBeta(board: Array<CharArray>, depth: Int, alphaIn: Int, betaIn: Int, whiteToMove: Boolean): Int {
        if (depth <= 0) return quiescenceSearch(board, alphaIn, betaIn, whiteToMove, 2)
        val moves = generatePseudoMoves(board, whiteToMove, "-")
        if (moves.isEmpty()) return evaluatePosition(board)

        var alpha = alphaIn
        var beta = betaIn

        if (whiteToMove) {
            var maxEval = -999999
            for (m in moves) {
                val child = applyMove(board, m)
                if (isKingInCheck(child, true)) continue
                val ev = alphaBeta(child, depth - 1, alpha, beta, false)
                maxEval = max(maxEval, ev)
                alpha = max(alpha, ev)
                if (beta <= alpha) break
            }
            return if (maxEval == -999999) evaluatePosition(board) else maxEval
        } else {
            var minEval = 999999
            for (m in moves) {
                val child = applyMove(board, m)
                if (isKingInCheck(child, false)) continue
                val ev = alphaBeta(child, depth - 1, alpha, beta, true)
                minEval = min(minEval, ev)
                beta = min(beta, ev)
                if (beta <= alpha) break
            }
            return if (minEval == 999999) evaluatePosition(board) else minEval
        }
    }

    /**
     * Форсированный расчет всех взятий на концах веток (исключает «эффект горизонта» и зевки фигур).
     */
    private fun quiescenceSearch(board: Array<CharArray>, alphaIn: Int, betaIn: Int, whiteToMove: Boolean, qDepth: Int): Int {
        val standPat = evaluatePosition(board)
        if (qDepth <= 0) return standPat

        var alpha = alphaIn
        var beta = betaIn

        if (whiteToMove) {
            if (standPat >= beta) return beta
            if (alpha < standPat) alpha = standPat

            val captures = generatePseudoMoves(board, true, "-").filter { it.isCapture }
            for (m in captures) {
                val child = applyMove(board, m)
                if (isKingInCheck(child, true)) continue
                val score = quiescenceSearch(child, alpha, beta, false, qDepth - 1)
                if (score >= beta) return beta
                if (score > alpha) alpha = score
            }
            return alpha
        } else {
            if (standPat <= alpha) return alpha
            if (beta > standPat) beta = standPat

            val captures = generatePseudoMoves(board, false, "-").filter { it.isCapture }
            for (m in captures) {
                val child = applyMove(board, m)
                if (isKingInCheck(child, false)) continue
                val score = quiescenceSearch(child, alpha, beta, true, qDepth - 1)
                if (score <= alpha) return alpha
                if (score < beta) beta = score
            }
            return beta
        }
    }

    private fun evaluatePosition(board: Array<CharArray>): Int {
        var score = 0
        for (r in 0..7) {
            for (c in 0..7) {
                val p = board[r][c]
                if (p == '.') continue
                score += PIECE_VALUES[p] ?: 0
                val isW = p.isUpperCase()
                val pstRow = if (isW) r else 7 - r
                val bonus = when (p.uppercaseChar()) {
                    'P' -> PAWN_PST[pstRow][c]
                    'N' -> KNIGHT_PST[pstRow][c]
                    'B', 'Q' -> BISHOP_PST[pstRow][c]
                    else -> 0
                }
                if (isW) score += bonus else score -= bonus
            }
        }
        return score
    }

    private fun generateLegalMoves(board: Array<CharArray>, white: Boolean, castling: String): List<Move> {
        val pseudo = generatePseudoMoves(board, white, castling)
        val legal = pseudo.filter { m ->
            val next = applyMove(board, m)
            !isKingInCheck(next, white)
        }
        return legal.ifEmpty { pseudo }
    }

    private fun generatePseudoMoves(board: Array<CharArray>, white: Boolean, castling: String): List<Move> {
        val list = mutableListOf<Move>()
        for (r in 0..7) {
            for (c in 0..7) {
                val p = board[r][c]
                if (p == '.' || p.isUpperCase() != white) continue
                when (p.uppercaseChar()) {
                    'P' -> {
                        val dir = if (white) -1 else 1
                        val startRank = if (white) 6 else 1
                        val promoRank = if (white) 0 else 7
                        val nr = r + dir
                        if (nr in 0..7 && board[nr][c] == '.') {
                            list.add(Move(r, c, nr, c, if (nr == promoRank) 'q' else null))
                            if (r == startRank && board[r + dir * 2][c] == '.') {
                                list.add(Move(r, c, r + dir * 2, c))
                            }
                        }
                        for (dc in intArrayOf(-1, 1)) {
                            val nc = c + dc
                            if (nr in 0..7 && nc in 0..7) {
                                val target = board[nr][nc]
                                if (target != '.' && target.isUpperCase() != white) {
                                    list.add(Move(r, c, nr, nc, if (nr == promoRank) 'q' else null, isCapture = true))
                                }
                            }
                        }
                    }
                    'N' -> {
                        val jumps = arrayOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
                        for ((dr, dc) in jumps) {
                            val nr = r + dr; val nc = c + dc
                            if (nr in 0..7 && nc in 0..7) {
                                val t = board[nr][nc]
                                if (t == '.' || t.isUpperCase() != white) {
                                    list.add(Move(r, c, nr, nc, isCapture = t != '.'))
                                }
                            }
                        }
                    }
                    'K' -> {
                        for (dr in -1..1) {
                            for (dc in -1..1) {
                                if (dr == 0 && dc == 0) continue
                                val nr = r + dr; val nc = c + dc
                                if (nr in 0..7 && nc in 0..7) {
                                    val t = board[nr][nc]
                                    if (t == '.' || t.isUpperCase() != white) {
                                        list.add(Move(r, c, nr, nc, isCapture = t != '.'))
                                    }
                                }
                            }
                        }
                        if (white && r == 7 && c == 4) {
                            if (castling.contains('K') && board[7][5] == '.' && board[7][6] == '.' && board[7][7] == 'R') {
                                list.add(Move(7, 4, 7, 6, isCastling = true))
                            }
                            if (castling.contains('Q') && board[7][3] == '.' && board[7][2] == '.' && board[7][1] == '.' && board[7][0] == 'R') {
                                list.add(Move(7, 4, 7, 2, isCastling = true))
                            }
                        } else if (!white && r == 0 && c == 4) {
                            if (castling.contains('k') && board[0][5] == '.' && board[0][6] == '.' && board[0][7] == 'r') {
                                list.add(Move(0, 4, 0, 6, isCastling = true))
                            }
                            if (castling.contains('q') && board[0][3] == '.' && board[0][2] == '.' && board[0][1] == '.' && board[0][0] == 'r') {
                                list.add(Move(0, 4, 0, 2, isCastling = true))
                            }
                        }
                    }
                    'B', 'R', 'Q' -> {
                        val dirs = mutableListOf<Pair<Int, Int>>()
                        if (p.uppercaseChar() != 'B') dirs.addAll(listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
                        if (p.uppercaseChar() != 'R') dirs.addAll(listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1))
                        for ((dr, dc) in dirs) {
                            var nr = r + dr; var nc = c + dc
                            while (nr in 0..7 && nc in 0..7) {
                                val t = board[nr][nc]
                                if (t == '.') {
                                    list.add(Move(r, c, nr, nc))
                                } else {
                                    if (t.isUpperCase() != white) list.add(Move(r, c, nr, nc, isCapture = true))
                                    break
                                }
                                nr += dr; nc += dc
                            }
                        }
                    }
                }
            }
        }
        // Сортировка ходов по MVV-LVA (сначала взятия самых дорогих фигур самыми дешевыми)
        return list.sortedByDescending { m ->
            val victim = board[m.r2][m.c2]
            val attacker = board[m.r1][m.c1]
            val captureValue = if (victim != '.') abs(PIECE_VALUES[victim] ?: 100) * 10 - abs(PIECE_VALUES[attacker] ?: 100) else 0
            (if (m.isCastling) 600 else 0) + captureValue
        }
    }

    private fun applyMove(board: Array<CharArray>, m: Move): Array<CharArray> {
        val next = Array(8) { r -> board[r].clone() }
        val p = next[m.r1][m.c1]
        next[m.r1][m.c1] = '.'
        next[m.r2][m.c2] = if (m.promo != null) {
            if (p.isUpperCase()) m.promo.uppercaseChar() else m.promo
        } else p

        if (m.isCastling) {
            if (m.c2 == 6) {
                next[m.r1][5] = next[m.r1][7]
                next[m.r1][7] = '.'
            } else if (m.c2 == 2) {
                next[m.r1][3] = next[m.r1][0]
                next[m.r1][0] = '.'
            }
        }
        return next
    }

    private fun isKingInCheck(board: Array<CharArray>, whiteKing: Boolean): Boolean {
        val targetKing = if (whiteKing) 'K' else 'k'
        var kr = -1; var kc = -1
        for (r in 0..7) {
            for (c in 0..7) {
                if (board[r][c] == targetKing) { kr = r; kc = c; break }
            }
        }
        if (kr == -1) return true
        val oppMoves = generatePseudoMoves(board, !whiteKing, "-")
        return oppMoves.any { it.r2 == kr && it.c2 == kc }
    }

    private fun parseFenBoard(fenBoard: String): Array<CharArray> {
        val grid = Array(8) { CharArray(8) { '.' } }
        val rows = fenBoard.split("/")
        for (r in 0 until min(8, rows.size)) {
            var c = 0
            for (ch in rows[r]) {
                if (ch.isDigit()) {
                    c += ch.digitToInt()
                } else if (c < 8) {
                    grid[r][c] = ch
                    c++
                }
            }
        }
        return grid
    }
}
