package com.chessbot.assistant

import android.content.Context
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ChessEngine(private val context: Context? = null) {

    data class EngineResult(
        val bestMove: String,
        val ponderMove: String = "",
        val evalCp: Int = 0
    )

    data class Move(
        val fromR: Int, val fromC: Int,
        val toR: Int, val toC: Int,
        val promo: Char = '.',
        var score: Int = 0
    ) {
        fun toUci(): String {
            val f1 = ('a' + fromC)
            val r1 = ('1' + (7 - fromR))
            val f2 = ('a' + toC)
            val r2 = ('1' + (7 - toR))
            return if (promo != '.') "$f1$r1$f2$r2${promo.lowercaseChar()}" else "$f1$r1$f2$r2"
        }
    }

    private val pieceVal = IntArray(128).apply {
        this['P'.code] = 100; this['p'.code] = 100
        this['N'.code] = 320; this['n'.code] = 320
        this['B'.code] = 330; this['b'.code] = 330
        this['R'.code] = 500; this['r'.code] = 500
        this['Q'.code] = 900; this['q'.code] = 900
        this['K'.code] = 20000; this['k'.code] = 20000
    }

    fun analyze(board: Array<CharArray>, whiteToMove: Boolean, maxDepth: Int = 5): EngineResult {
        val rootMoves = generateLegalMoves(board, whiteToMove)
        if (rootMoves.isEmpty()) return EngineResult("", "", 0)

        var bestMove = rootMoves[0]
        var bestScore = if (whiteToMove) -99999 else 99999

        for (d in 1..maxDepth) {
            var alpha = -100000
            var beta = 100000
            var currentBest = bestMove
            var currentBestScore = if (whiteToMove) -99999 else 99999

            val ordered = rootMoves.sortedByDescending {
                if (it.toUci() == bestMove.toUci()) 1000000 else it.score
            }

            for (m in ordered) {
                val captured = board[m.toR][m.toC]
                val moved = board[m.fromR][m.fromC]
                board[m.toR][m.toC] = if (m.promo != '.') m.promo else moved
                board[m.fromR][m.fromC] = '.'

                val score = alphaBeta(board, d - 1, alpha, beta, !whiteToMove)

                board[m.fromR][m.fromC] = moved
                board[m.toR][m.toC] = captured

                if (whiteToMove) {
                    if (score > currentBestScore) {
                        currentBestScore = score
                        currentBest = m
                    }
                    alpha = max(alpha, score)
                } else {
                    if (score < currentBestScore) {
                        currentBestScore = score
                        currentBest = m
                    }
                    beta = min(beta, score)
                }
            }
            bestMove = currentBest
            bestScore = currentBestScore
        }

        // Вычисляем ответный ход (ponder)
        val cap = board[bestMove.toR][bestMove.toC]
        val mov = board[bestMove.fromR][bestMove.fromC]
        board[bestMove.toR][bestMove.toC] = if (bestMove.promo != '.') bestMove.promo else mov
        board[bestMove.fromR][bestMove.fromC] = '.'
        val replyMoves = generateLegalMoves(board, !whiteToMove)
        var ponderUci = ""
        if (replyMoves.isNotEmpty()) {
            ponderUci = replyMoves.maxByOrNull { it.score }?.toUci() ?: ""
        }
        board[bestMove.fromR][bestMove.fromC] = mov
        board[bestMove.toR][bestMove.toC] = cap

        return EngineResult(bestMove.toUci(), ponderUci, bestScore)
    }

    private fun alphaBeta(
        board: Array<CharArray>,
        depth: Int,
        alphaInit: Int,
        betaInit: Int,
        whiteToMove: Boolean
    ): Int {
        if (depth <= 0) return evaluate(board)
        var alpha = alphaInit
        var beta = betaInit

        val moves = generatePseudoMoves(board, whiteToMove)
        if (moves.isEmpty()) return evaluate(board)

        if (whiteToMove) {
            var value = -99999
            for (m in moves) {
                val cap = board[m.toR][m.toC]
                if (cap == 'k' || cap == 'K') return 90000 + depth * 100
                val mov = board[m.fromR][m.fromC]
                board[m.toR][m.toC] = if (m.promo != '.') m.promo else mov
                board[m.fromR][m.fromC] = '.'

                value = max(value, alphaBeta(board, depth - 1, alpha, beta, false))

                board[m.fromR][m.fromC] = mov
                board[m.toR][m.toC] = cap

                alpha = max(alpha, value)
                if (alpha >= beta) break
            }
            return value
        } else {
            var value = 99999
            for (m in moves) {
                val cap = board[m.toR][m.toC]
                if (cap == 'K' || cap == 'k') return -90000 - depth * 100
                val mov = board[m.fromR][m.fromC]
                board[m.toR][m.toC] = if (m.promo != '.') m.promo else mov
                board[m.fromR][m.fromC] = '.'

                value = min(value, alphaBeta(board, depth - 1, alpha, beta, true))

                board[m.fromR][m.fromC] = mov
                board[m.toR][m.toC] = cap

                beta = min(beta, value)
                if (beta <= alpha) break
            }
            return value
        }
    }

    private fun evaluate(board: Array<CharArray>): Int {
        var score = 0
        for (r in 0..7) {
            for (c in 0..7) {
                val p = board[r][c]
                if (p == '.') continue
                val isW = p.isUpperCase()
                var v = pieceVal[p.code]

                // Позиционный бонус за центр и продвижение
                val centerBonus = (3 - abs(3 - c)) * 6 + (3 - abs(3 - r)) * 6
                when (p.lowercaseChar()) {
                    'p' -> {
                        val adv = if (isW) (6 - r) else (r - 1)
                        v += adv * 12 + centerBonus
                    }
                    'n', 'b' -> v += centerBonus * 2
                    'q', 'r' -> v += centerBonus
                }
                if (isW) score += v else score -= v
            }
        }
        return score
    }

    fun generateLegalMoves(board: Array<CharArray>, whiteToMove: Boolean): List<Move> {
        val pseudo = generatePseudoMoves(board, whiteToMove)
        val legal = ArrayList<Move>(pseudo.size)
        for (m in pseudo) {
            val cap = board[m.toR][m.toC]
            val mov = board[m.fromR][m.fromC]
            board[m.toR][m.toC] = if (m.promo != '.') m.promo else mov
            board[m.fromR][m.fromC] = '.'

            val inCheck = isKingInCheck(board, whiteToMove)

            board[m.fromR][m.fromC] = mov
            board[m.toR][m.toC] = cap

            if (!inCheck) legal.add(m)
        }
        return legal.ifEmpty { pseudo }
    }

    private fun isKingInCheck(board: Array<CharArray>, whiteKing: Boolean): Boolean {
        val kChar = if (whiteKing) 'K' else 'k'
        var kr = -1; var kc = -1
        for (r in 0..7) {
            for (c in 0..7) {
                if (board[r][c] == kChar) { kr = r; kc = c; break }
            }
        }
        if (kr == -1) return false
        val enemyMoves = generatePseudoMoves(board, !whiteKing)
        return enemyMoves.any { it.toR == kr && it.toC == kc }
    }

    private fun generatePseudoMoves(board: Array<CharArray>, whiteToMove: Boolean): List<Move> {
        val moves = ArrayList<Move>(48)
        for (r in 0..7) {
            for (c in 0..7) {
                val p = board[r][c]
                if (p == '.' || p.isUpperCase() != whiteToMove) continue
                when (p.lowercaseChar()) {
                    'p' -> {
                        val dir = if (whiteToMove) -1 else 1
                        val startRank = if (whiteToMove) 6 else 1
                        val promoRank = if (whiteToMove) 0 else 7
                        val promoChar = if (whiteToMove) 'Q' else 'q'
                        val nr = r + dir
                        if (nr in 0..7) {
                            if (board[nr][c] == '.') {
                                val pr = if (nr == promoRank) promoChar else '.'
                                moves.add(Move(r, c, nr, c, pr, if (pr != '.') 800 else 10))
                                val nnr = r + dir * 2
                                if (r == startRank && nnr in 0..7 && board[nnr][c] == '.') {
                                    moves.add(Move(r, c, nnr, c, '.', 15))
                                }
                            }
                            for (dc in intArrayOf(-1, 1)) {
                                val nc = c + dc
                                if (nc in 0..7) {
                                    val t = board[nr][nc]
                                    if (t != '.' && t.isUpperCase() != whiteToMove) {
                                        val pr = if (nr == promoRank) promoChar else '.'
                                        moves.add(Move(r, c, nr, nc, pr, pieceVal[t.code] * 10 - 100))
                                    }
                                }
                            }
                        }
                    }
                    'n' -> {
                        val dr = intArrayOf(-2, -2, -1, -1, 1, 1, 2, 2)
                        val dc = intArrayOf(-1, 1, -2, 2, -2, 2, -1, 1)
                        for (i in 0..7) {
                            val nr = r + dr[i]; val nc = c + dc[i]
                            if (nr in 0..7 && nc in 0..7) {
                                val t = board[nr][nc]
                                if (t == '.' || t.isUpperCase() != whiteToMove) {
                                    val sc = if (t != '.') pieceVal[t.code] * 10 - 320 else 20
                                    moves.add(Move(r, c, nr, nc, '.', sc))
                                }
                            }
                        }
                    }
                    'k' -> {
                        for (dr in -1..1) {
                            for (dc in -1..1) {
                                if (dr == 0 && dc == 0) continue
                                val nr = r + dr; val nc = c + dc
                                if (nr in 0..7 && nc in 0..7) {
                                    val t = board[nr][nc]
                                    if (t == '.' || t.isUpperCase() != whiteToMove) {
                                        val sc = if (t != '.') pieceVal[t.code] * 10 - 100 else 5
                                        moves.add(Move(r, c, nr, nc, '.', sc))
                                    }
                                }
                            }
                        }
                    }
                    'b', 'r', 'q' -> {
                        val dirs = when (p.lowercaseChar()) {
                            'b' -> arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
                            'r' -> arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
                            else -> arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1, -1 to 0, 1 to 0, 0 to -1, 0 to 1)
                        }
                        for ((dr, dc) in dirs) {
                            var nr = r + dr; var nc = c + dc
                            while (nr in 0..7 && nc in 0..7) {
                                val t = board[nr][nc]
                                if (t == '.') {
                                    moves.add(Move(r, c, nr, nc, '.', 12))
                                } else {
                                    if (t.isUpperCase() != whiteToMove) {
                                        moves.add(Move(r, c, nr, nc, '.', pieceVal[t.code] * 10 - pieceVal[p.code]))
                                    }
                                    break
                                }
                                nr += dr; nc += dc
                            }
                        }
                    }
                }
            }
        }
        moves.sortByDescending { it.score }
        return moves
    }
}
