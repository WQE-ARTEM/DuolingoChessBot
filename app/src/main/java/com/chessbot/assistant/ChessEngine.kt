package com.chessbot.assistant

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.math.abs
import kotlin.math.max

class ChessEngine(private val context: Context? = null) {

    data class AnalysisResult(
        val bestMoveUci: String,
        val ponderMoveUci: String = "",
        val evalScoreCp: Int = 0,
        val engineName: String = "KillerEngine MAX"
    )

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null
    private var stockfishReady = false

    init {
        initNativeStockfishIfPresent()
    }

    private fun initNativeStockfishIfPresent() {
        try {
            val nativeDir = context?.applicationInfo?.nativeLibraryDir
            val candidates = listOfNotNull(
                nativeDir?.let { File(it, "libstockfish.so") },
                nativeDir?.let { File(it, "stockfish") },
                context?.filesDir?.let { File(it, "stockfish") }
            )
            val bin = candidates.firstOrNull { it.exists() } ?: return
            bin.setExecutable(true)
            process = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            writer = OutputStreamWriter(process!!.outputStream)

            sendUci("uci")
            sendUci("setoption name Threads value 4")
            sendUci("setoption name Hash value 128")
            sendUci("setoption name Skill Level value 20")
            sendUci("setoption name Contempt value 100")
            sendUci("setoption name MultiPV value 1")
            sendUci("isready")
            stockfishReady = true
        } catch (_: Exception) {
            stockfishReady = false
        }
    }

    private fun sendUci(cmd: String) {
        writer?.apply {
            write("$cmd\n")
            flush()
        }
    }

    fun analyzePosition(fen: String, timeLimitMs: Int = 900): AnalysisResult {
        if (stockfishReady && process != null) {
            try {
                sendUci("position fen $fen")
                sendUci("go movetime ${max(timeLimitMs, 800)} depth 22")
                var bestMove = ""
                var ponderMove = ""
                var scoreCp = 0
                var line: String?
                while (reader?.readLine().also { line = it } != null) {
                    val l = line!!
                    if (l.contains("score cp ")) {
                        val after = l.substringAfter("score cp ").trim().split(" ")[0]
                        scoreCp = after.toIntOrNull() ?: scoreCp
                    } else if (l.contains("score mate ")) {
                        val m = l.substringAfter("score mate ").trim().split(" ")[0].toIntOrNull() ?: 1
                        scoreCp = if (m > 0) 9900 else -9900
                    }
                    if (l.startsWith("bestmove")) {
                        val parts = l.split(" ")
                        bestMove = parts.getOrNull(1) ?: ""
                        if (parts.size >= 4 && parts[2] == "ponder") {
                            ponderMove = parts[3]
                        }
                        break
                    }
                }
                if (bestMove.length >= 4 && bestMove != "(none)") {
                    return AnalysisResult(bestMove, ponderMove, scoreCp, "Stockfish 16 MAX")
                }
            } catch (_: Exception) {
                stockfishReady = false
            }
        }
        return searchMasterMove(fen, max(timeLimitMs, 750))
    }

    fun close() {
        try {
            sendUci("quit")
            process?.destroy()
        } catch (_: Exception) {}
    }

    private data class Move(val from: Int, val to: Int, val promo: Char = ' ', var score: Int = 0) {
        fun toUci(): String {
            val fCol = 'a' + (from % 8)
            val fRow = '8' - (from / 8)
            val tCol = 'a' + (to % 8)
            val tRow = '8' - (to / 8)
            val p = if (promo != ' ') promo.toString() else ""
            return "$fCol$fRow$tCol$tRow$p"
        }
    }

    private val pstPawn = intArrayOf(
         0,  0,  0,  0,  0,  0,  0,  0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 35, 35, 20, 10, 10,
         5,  5, 10, 28, 28, 10,  5,  5,
         0,  0,  0, 24, 24,  0,  0,  0,
         5, -5,-10,  0,  0,-10, -5,  5,
         5, 10, 10,-20,-20, 10, 10,  5,
         0,  0,  0,  0,  0,  0,  0,  0
    )

    private val pstKnight = intArrayOf(
        -50,-40,-30,-30,-30,-30,-40,-50,
        -40,-20,  0,  0,  0,  0,-20,-40,
        -30,  0, 15, 20, 20, 15,  0,-30,
        -30,  5, 20, 25, 25, 20,  5,-30,
        -30,  0, 20, 25, 25, 20,  0,-30,
        -30,  5, 15, 20, 20, 15,  5,-30,
        -40,-20,  0,  5,  5,  0,-20,-40,
        -50,-40,-30,-30,-30,-30,-40,-50
    )

    private val pstBishop = intArrayOf(
        -20,-10,-10,-10,-10,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0, 10, 15, 15, 10,  0,-10,
        -10, 10, 10, 15, 15, 10, 10,-10,
        -10,  0, 15, 15, 15, 15,  0,-10,
        -10, 15, 15, 15, 15, 15, 15,-10,
        -10, 10,  0,  0,  0,  0, 10,-10,
        -20,-10,-10,-10,-10,-10,-10,-20
    )

    private val pstKingMid = intArrayOf(
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -20,-30,-30,-40,-40,-30,-30,-20,
        -10,-20,-20,-20,-20,-20,-20,-10,
         20, 20,  0,  0,  0,  0, 20, 20,
         20, 30, 10,  0,  0, 10, 30, 20
    )

    private fun pieceValue(c: Char): Int = when (c.lowercaseChar()) {
        'p' -> 100
        'n' -> 320
        'b' -> 335
        'r' -> 500
        'q' -> 950
        'k' -> 20000
        else -> 0
    }

    private fun searchMasterMove(fen: String, timeLimitMs: Int): AnalysisResult {
        val board = CharArray(64) { '.' }
        val parts = fen.trim().split(" ")
        val rows = parts[0].split("/")
        var idx = 0
        for (r in rows) {
            for (ch in r) {
                if (ch.isDigit()) idx += ch.digitToInt()
                else if (idx < 64) board[idx++] = ch
            }
        }
        val whiteToMove = (parts.getOrNull(1) ?: "w") != "b"
        val startTime = System.currentTimeMillis()
        var bestMove: Move? = null
        var ponderMove: Move? = null
        var bestEval = 0

        val rootMoves = generateLegalMoves(board, whiteToMove)
        if (rootMoves.isEmpty()) return AnalysisResult("e2e4", "", 0, "KillerEngine MAX")

        bestMove = rootMoves[0]
        for (depth in 1..7) {
            if (System.currentTimeMillis() - startTime > timeLimitMs) break
            var alpha = -50000
            val beta = 50000
            var currentBest: Move? = null
            var currentScore = -50000

            val ordered = rootMoves.sortedByDescending { m ->
                if (m == bestMove) 100000 else scoreMove(board, m)
            }

            for (m in ordered) {
                val next = board.clone()
                applyMove(next, m)
                val sc = -negamax(next, depth - 1, -beta, -alpha, !whiteToMove, startTime, timeLimitMs)
                if (System.currentTimeMillis() - startTime > timeLimitMs && depth > 3) break
                if (sc > currentScore) {
                    currentScore = sc
                    currentBest = m
                }
                if (sc > alpha) alpha = sc
            }

            if (currentBest != null) {
                bestMove = currentBest
                bestEval = if (whiteToMove) currentScore else -currentScore
                val afterBest = board.clone()
                applyMove(afterBest, bestMove)
                val replyMoves = generateLegalMoves(afterBest, !whiteToMove)
                ponderMove = replyMoves.maxByOrNull { scoreMove(afterBest, it) }
            }
            if (abs(currentScore) > 15000) break
        }

        return AnalysisResult(
            bestMoveUci = bestMove!!.toUci(),
            ponderMoveUci = ponderMove?.toUci() ?: "",
            evalScoreCp = bestEval,
            engineName = "Stockfish/Killer v6"
        )
    }

    private fun negamax(
        b: CharArray,
        depth: Int,
        alphaInit: Int,
        beta: Int,
        whiteTurn: Boolean,
        startTime: Long,
        limitMs: Int
    ): Int {
        if (depth <= 0) return quiescence(b, alphaInit, beta, whiteTurn, 4)
        if (System.currentTimeMillis() - startTime > limitMs) return evaluate(b, whiteTurn)

        val moves = generateLegalMoves(b, whiteTurn)
        if (moves.isEmpty()) {
            return if (isKingInCheck(b, whiteTurn)) -19000 - depth * 10 else 0
        }

        var alpha = alphaInit
        val ordered = moves.sortedByDescending { scoreMove(b, it) }
        var maxScore = -50000

        for (m in ordered) {
            val next = b.clone()
            applyMove(next, m)
            val sc = -negamax(next, depth - 1, -beta, -alpha, !whiteTurn, startTime, limitMs)
            if (sc > maxScore) maxScore = sc
            if (sc > alpha) alpha = sc
            if (alpha >= beta) break
        }
        return maxScore
    }

    private fun quiescence(b: CharArray, alphaInit: Int, beta: Int, whiteTurn: Boolean, qDepth: Int): Int {
        val standPat = evaluate(b, whiteTurn)
        if (qDepth <= 0 || standPat >= beta) return standPat
        var alpha = max(alphaInit, standPat)

        val captures = generateLegalMoves(b, whiteTurn).filter { b[it.to] != '.' || it.promo != ' ' }
            .sortedByDescending { scoreMove(b, it) }

        for (m in captures) {
            val next = b.clone()
            applyMove(next, m)
            val score = -quiescence(next, -beta, -alpha, !whiteTurn, qDepth - 1)
            if (score >= beta) return beta
            if (score > alpha) alpha = score
        }
        return alpha
    }

    private fun evaluate(b: CharArray, whiteTurn: Boolean): Int {
        var score = 0
        for (i in 0 until 64) {
            val p = b[i]
            if (p == '.') continue
            val isWhite = p.isUpperCase()
            val sq = if (isWhite) i else (7 - (i / 8)) * 8 + (i % 8)
            val base = pieceValue(p)
            val pos = when (p.lowercaseChar()) {
                'p' -> pstPawn[sq]
                'n' -> pstKnight[sq]
                'b' -> pstBishop[sq]
                'k' -> pstKingMid[sq]
                'q', 'r' -> if (i % 8 in 2..5 && i / 8 in 2..5) 15 else 0
                else -> 0
            }
            if (isWhite) score += base + pos else score -= (base + pos)
        }
        return if (whiteTurn) score else -score
    }

    private fun scoreMove(b: CharArray, m: Move): Int {
        var s = 0
        val victim = b[m.to]
        val attacker = b[m.from]
        if (victim != '.') s += 10 * pieceValue(victim) - pieceValue(attacker) / 10
        if (m.promo != ' ') s += 900
        return s
    }

    private fun applyMove(b: CharArray, m: Move) {
        val p = b[m.from]
        b[m.from] = '.'
        b[m.to] = if (m.promo != ' ') {
            if (p.isUpperCase()) m.promo.uppercaseChar() else m.promo.lowercaseChar()
        } else p
    }

    private fun generateLegalMoves(b: CharArray, whiteTurn: Boolean): List<Move> {
        val pseudo = generatePseudoMoves(b, whiteTurn)
        val legal = ArrayList<Move>(pseudo.size)
        for (m in pseudo) {
            val next = b.clone()
            applyMove(next, m)
            if (!isKingInCheck(next, whiteTurn)) legal.add(m)
        }
        return legal
    }

    private fun isKingInCheck(b: CharArray, whiteKing: Boolean): Boolean {
        val kingChar = if (whiteKing) 'K' else 'k'
        val kingSq = b.indexOfFirst { it == kingChar }
        if (kingSq == -1) return true
        val enemyMoves = generatePseudoMoves(b, !whiteKing)
        return enemyMoves.any { it.to == kingSq }
    }

    private fun generatePseudoMoves(b: CharArray, whiteTurn: Boolean): List<Move> {
        val list = ArrayList<Move>(48)
        for (i in 0 until 64) {
            val p = b[i]
            if (p == '.' || p.isUpperCase() != whiteTurn) continue
            val r = i / 8
            val c = i % 8
            when (p.lowercaseChar()) {
                'p' -> {
                    val dir = if (whiteTurn) -1 else 1
                    val startRow = if (whiteTurn) 6 else 1
                    val promoRow = if (whiteTurn) 0 else 7
                    val nr = r + dir
                    if (nr in 0..7) {
                        val one = nr * 8 + c
                        if (b[one] == '.') {
                            if (nr == promoRow) list.add(Move(i, one, 'q'))
                            else {
                                list.add(Move(i, one))
                                val two = (r + dir * 2) * 8 + c
                                if (r == startRow && b[two] == '.') list.add(Move(i, two))
                            }
                        }
                        for (dc in intArrayOf(-1, 1)) {
                            val nc = c + dc
                            if (nc in 0..7) {
                                val cap = nr * 8 + nc
                                val target = b[cap]
                                if (target != '.' && target.isUpperCase() != whiteTurn) {
                                    if (nr == promoRow) list.add(Move(i, cap, 'q'))
                                    else list.add(Move(i, cap))
                                }
                            }
                        }
                    }
                }
                'n' -> {
                    val jumps = arrayOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
                    for ((dr, dc) in jumps) {
                        val nr = r + dr; val nc = c + dc
                        if (nr in 0..7 && nc in 0..7) {
                            val t = b[nr * 8 + nc]
                            if (t == '.' || t.isUpperCase() != whiteTurn) list.add(Move(i, nr * 8 + nc))
                        }
                    }
                }
                'k' -> {
                    for (dr in -1..1) for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val nr = r + dr; val nc = c + dc
                        if (nr in 0..7 && nc in 0..7) {
                            val t = b[nr * 8 + nc]
                            if (t == '.' || t.isUpperCase() != whiteTurn) list.add(Move(i, nr * 8 + nc))
                        }
                    }
                }
                'b', 'r', 'q' -> {
                    val dirs = mutableListOf<Pair<Int, Int>>()
                    if (p.lowercaseChar() != 'b') dirs.addAll(listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
                    if (p.lowercaseChar() != 'r') dirs.addAll(listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1))
                    for ((dr, dc) in dirs) {
                        var nr = r + dr; var nc = c + dc
                        while (nr in 0..7 && nc in 0..7) {
                            val sq = nr * 8 + nc
                            val t = b[sq]
                            if (t == '.') {
                                list.add(Move(i, sq))
                            } else {
                                if (t.isUpperCase() != whiteTurn) list.add(Move(i, sq))
                                break
                            }
                            nr += dr; nc += dc
                        }
                    }
                }
            }
        }
        return list
    }
}
