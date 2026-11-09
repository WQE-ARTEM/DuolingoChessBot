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

    init { initStockfish() }

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
                sendUciCommand("setoption name Threads value 2")
                sendUciCommand("setoption name Hash value 32")
                sendUciCommand("isready")

                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 1500) {
                    val line = sfReader?.readLine() ?: break
                    if (line.contains("readyok")) { stockfishReady = true; break }
                }
            }
        } catch (e: Exception) { stockfishReady = false }
    }

    private fun sendUciCommand(cmd: String) {
        sfWriter?.write("$cmd\n"); sfWriter?.flush()
    }

    fun analyzePosition(fen: String, moveTimeMs: Int = 380): EngineAnalysis {
        if (stockfishReady) {
            val sfResult = analyzeWithStockfish(fen, moveTimeMs)
            if (sfResult != null) return sfResult
        }
        return KotlinFallbackEngine.findBestMove(fen)
    }

    private fun analyzeWithStockfish(fen: String, moveTimeMs: Int): EngineAnalysis? {
        return try {
            sendUciCommand("position fen $fen")
            sendUciCommand("go movetime $moveTimeMs")
            var bestMove = ""
            var ponderMove: String? = null
            var scoreCp = 0
            val whiteTurn = fen.contains(" w ")
            val deadline = System.currentTimeMillis() + moveTimeMs + 800
            while (System.currentTimeMillis() < deadline) {
                val line = sfReader?.readLine() ?: break
                if (line.startsWith("info") && line.contains("score ")) {
                    val parts = line.split(" ")
                    val idx = parts.indexOf("score")
                    if (idx != -1 && idx + 2 < parts.size) {
                        val type = parts[idx + 1]
                        val rawVal = parts[idx + 2].toIntOrNull() ?: 0
                        scoreCp = if (type == "mate") (if (rawVal > 0) 9900 else -9900) else rawVal
                        if (!whiteTurn) scoreCp = -scoreCp
                    }
                } else if (line.startsWith("bestmove")) {
                    val parts = line.split(" ")
                    if (parts.size >= 2) bestMove = parts[1]
                    val pIdx = parts.indexOf("ponder")
                    if (pIdx != -1 && pIdx + 1 < parts.size) ponderMove = parts[pIdx + 1]
                    break
                }
            }
            if (bestMove.length >= 4 && bestMove != "(none)")
                EngineAnalysis(bestMove, ponderMove, scoreCp, "Stockfish 16")
            else null
        } catch (e: Exception) { stockfishReady = false; null }
    }

    fun close() {
        try { sendUciCommand("quit"); sfProcess?.destroy() } catch (_: Exception) {}
    }
}

object KotlinFallbackEngine {

    private val PIECE_VALUES = mapOf(
        'P' to 100, 'N' to 320, 'B' to 330, 'R' to 500, 'Q' to 900, 'K' to 20000,
        'p' to -100, 'n' to -320, 'b' to -330, 'r' to -500, 'q' to -900, 'k' to -20000
    )

    data class Move(val r1: Int, val c1: Int, val r2: Int, val c2: Int, val promo: Char? = null, val isCastling: Boolean = false) {
        fun toUci(): String {
            val f1 = ('a' + c1); val rk1 = ('8' - r1); val f2 = ('a' + c2); val rk2 = ('8' - r2)
            return "$f1$rk1$f2$rk2${promo ?: ""}"
        }
    }

    fun findBestMove(fen: String): EngineAnalysis {
        val parts = fen.split(" ")
        val board = parseFenBoard(parts[0])
        val whiteToMove = parts.getOrNull(1) != "b"
        val castling = parts.getOrNull(2) ?: "KQkq"

        val moves = generateLegalMoves(board, whiteToMove, castling)
        if (moves.isEmpty()) return EngineAnalysis("e1g1", null, 0, "KotlinEngine")

        var bestMove = moves[0]
        var bestReply: Move? = null
        var bestScore = if (whiteToMove) Int.MIN_VALUE / 2 else Int.MAX_VALUE / 2

        for (m in moves) {
            val nextBoard = applyMove(board, m)
            val score = alphaBeta(nextBoard, 3, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, !whiteToMove)
            val castlingBonus = if (m.isCastling) (if (whiteToMove) 65 else -65) else 0
            val totalScore = score + castlingBonus
            if (whiteToMove && totalScore > bestScore) { bestScore = totalScore; bestMove = m }
            else if (!whiteToMove && totalScore < bestScore) { bestScore = totalScore; bestMove = m }
        }

        val afterBest = applyMove(board, bestMove)
        val oppMoves = generateLegalMoves(afterBest, !whiteToMove, "-")
        if (oppMoves.isNotEmpty()) {
            bestReply = oppMoves.maxByOrNull { m ->
                val s = evaluate(applyMove(afterBest, m))
                if (!whiteToMove) s else -s
            }
        }

        return EngineAnalysis(
            bestMoveUci = bestMove.toUci(),
            ponderMoveUci = bestReply?.toUci(),
            evalScoreCp = bestScore,
            engineName = "Kotlin AlphaBeta"
        )
    }

    private fun alphaBeta(board: Array<CharArray>, depth: Int, alphaIn: Int, betaIn: Int, whiteToMove: Boolean): Int {
        if (depth == 0) return evaluate(board)
        val moves = generatePseudoMoves(board, whiteToMove, "-")
        if (moves.isEmpty()) return evaluate(board)

        var alpha = alphaIn; var beta = betaIn

        if (whiteToMove) {
            var maxEval = Int.MIN_VALUE / 2
            for (m in moves) {
                val ev = alphaBeta(applyMove(board, m), depth - 1, alpha, beta, false)
                maxEval = max(maxEval, ev); alpha = max(alpha, ev)
                if (beta <= alpha) break
            }
            return maxEval
        } else {
            var minEval = Int.MAX_VALUE / 2
            for (m in moves) {
                val ev = alphaBeta(applyMove(board, m), depth - 1, alpha, beta, true)
                minEval = min(minEval, ev); beta = min(beta, ev)
                if (beta <= alpha) break
            }
            return minEval
        }
    }

    private fun evaluate(board: Array<CharArray>): Int {
        var score = 0
        for (r in 0..7) for (c in 0..7) {
            val p = board[r][c]
            if (p == '.') continue
            score += PIECE_VALUES[p] ?: 0
            val centerBonus = (3 - abs(3 - r)) + (3 - abs(3 - c))
            if (p.isUpperCase()) score += centerBonus * 4 else score -= centerBonus * 4
        }
        return score
    }

    private fun generateLegalMoves(board: Array<CharArray>, white: Boolean, castling: String): List<Move> {
        val pseudo = generatePseudoMoves(board, white, castling)
        return pseudo.filter { m -> !isKingInCheck(applyMove(board, m), white) }.ifEmpty { pseudo }
    }

    private fun generatePseudoMoves(board: Array<CharArray>, white: Boolean, castling: String): List<Move> {
        val list = mutableListOf<Move>()
        for (r in 0..7) for (c in 0..7) {
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
                        if (r == startRank && board[r + dir * 2][c] == '.') list.add(Move(r, c, r + dir * 2, c))
                    }
                    for (dc in intArrayOf(-1, 1)) {
                        val nc = c + dc
                        if (nr in 0..7 && nc in 0..7) {
                            val target = board[nr][nc]
                            if (target != '.' && target.isUpperCase() != white)
                                list.add(Move(r, c, nr, nc, if (nr == promoRank) 'q' else null))
                        }
                    }
                }
                'N' -> {
                    val jumps = arrayOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
                    for ((dr, dc) in jumps) {
                        val nr = r + dr; val nc = c + dc
                        if (nr in 0..7 && nc in 0..7) {
                            val t = board[nr][nc]
                            if (t == '.' || t.isUpperCase() != white) list.add(Move(r, c, nr, nc))
                        }
                    }
                }
                'K' -> {
                    for (dr in -1..1) for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val nr = r + dr; val nc = c + dc
                        if (nr in 0..7 && nc in 0..7) {
                            val t = board[nr][nc]
                            if (t == '.' || t.isUpperCase() != white) list.add(Move(r, c, nr, nc))
                        }
                    }
                    if (white && r == 7 && c == 4) {
                        if (castling.contains('K') && board[7][5] == '.' && board[7][6] == '.' && board[7][7] == 'R')
                            list.add(Move(7, 4, 7, 6, isCastling = true))
                        if (castling.contains('Q') && board[7][3] == '.' && board[7][2] == '.' && board[7][1] == '.' && board[7][0] == 'R')
                            list.add(Move(7, 4, 7, 2, isCastling = true))
                    } else if (!white && r == 0 && c == 4) {
                        if (castling.contains('k') && board[0][5] == '.' && board[0][6] == '.' && board[0][7] == 'r')
                            list.add(Move(0, 4, 0, 6, isCastling = true))
                        if (castling.contains('q') && board[0][3] == '.' && board[0][2] == '.' && board[0][1] == '.' && board[0][0] == 'r')
                            list.add(Move(0, 4, 0, 2, isCastling = true))
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
                            if (t == '.') list.add(Move(r, c, nr, nc))
                            else {
                                if (t.isUpperCase() != white) list.add(Move(r, c, nr, nc))
                                break
                            }
                            nr += dr; nc += dc
                        }
                    }
                }
            }
        }
        return list.sortedByDescending { m ->
            val captured = board[m.r2][m.c2]
            (if (m.isCastling) 500 else 0) + (if (captured != '.') abs(PIECE_VALUES[captured] ?: 100) else 0)
        }
    }

    private fun applyMove(board: Array<CharArray>, m: Move): Array<CharArray> {
        val next = Array(8) { r -> board[r].clone() }
        val p = next[m.r1][m.c1]
        next[m.r1][m.c1] = '.'
        next[m.r2][m.c2] = if (m.promo != null) (if (p.isUpperCase()) m.promo.uppercaseChar() else m.promo) else p
        if (m.isCastling) {
            if (m.c2 == 6) { next[m.r1][5] = next[m.r1][7]; next[m.r1][7] = '.' }
            else if (m.c2 == 2) { next[m.r1][3] = next[m.r1][0]; next[m.r1][0] = '.' }
        }
        return next
    }

    private fun isKingInCheck(board: Array<CharArray>, whiteKing: Boolean): Boolean {
        val targetKing = if (whiteKing) 'K' else 'k'
        var kr = -1; var kc = -1
        for (r in 0..7) for (c in 0..7) if (board[r][c] == targetKing) { kr = r; kc = c }
        if (kr == -1) return true
        return generatePseudoMoves(board, !whiteKing, "-").any { it.r2 == kr && it.c2 == kc }
    }

    private fun parseFenBoard(fenBoard: String): Array<CharArray> {
        val grid = Array(8) { CharArray(8) { '.' } }
        val rows = fenBoard.split("/")
        for (r in 0 until min(8, rows.size)) {
            var c = 0
            for (ch in rows[r]) {
                if (ch.isDigit()) c += ch.digitToInt()
                else if (c < 8) { grid[r][c] = ch; c++ }
            }
        }
        return grid
    }
}
