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
        this['B'.code] = 335; this['b'.code] = 335
        this['R'.code] = 500; this['r'.code] = 500
        this['Q'.code] = 950; this['q'.code] = 950
        this['K'.code] = 20000; this['k'.code] = 20000
    }

    // Позиционные таблицы (для Белых, r=0 — 8-я горизонталь, r=7 — 1-я горизонталь)
    private val pawnPst = arrayOf(
        intArrayOf(0,  0,  0,  0,  0,  0,  0,  0),
        intArrayOf(50, 50, 50, 50, 50, 50, 50, 50),
        intArrayOf(12, 12, 24, 34, 34, 24, 12, 12),
        intArrayOf(6,  6, 14, 28, 28, 14,  6,  6),
        intArrayOf(0,  0,  4, 24, 24,  4,  0,  0),
        intArrayOf(4, -4, -8,  0,  0, -8, -4,  4),
        intArrayOf(4, 10, 10,-20,-20, 10, 10,  4),
        intArrayOf(0,  0,  0,  0,  0,  0,  0,  0)
    )

    private val knightPst = arrayOf(
        intArrayOf(-50,-40,-30,-30,-30,-30,-40,-50),
        intArrayOf(-40,-20,  0,  4,  4,  0,-20,-40),
        intArrayOf(-30,  4, 15, 20, 20, 15,  4,-30),
        intArrayOf(-30,  6, 20, 26, 26, 20,  6,-30),
        intArrayOf(-30,  4, 20, 26, 26, 20,  4,-30),
        intArrayOf(-30,  6, 15, 20, 20, 15,  6,-30),
        intArrayOf(-40,-20,  0,  4,  4,  0,-20,-40),
        intArrayOf(-50,-40,-30,-30,-30,-30,-40,-50)
    )

    private val bishopPst = arrayOf(
        intArrayOf(-20,-10,-10,-10,-10,-10,-10,-20),
        intArrayOf(-10,  4,  0,  0,  0,  0,  4,-10),
        intArrayOf(-10,  8, 12, 14, 14, 12,  8,-10),
        intArrayOf(-10,  4, 14, 16, 16, 14,  4,-10),
        intArrayOf(-10,  6, 10, 16, 16, 10,  6,-10),
        intArrayOf(-10, 12, 12, 14, 14, 12, 12,-10),
        intArrayOf(-10,  8,  0,  0,  0,  0,  8,-10),
        intArrayOf(-20,-10,-10,-10,-10,-10,-10,-20)
    )

    fun analyze(board: Array<CharArray>, whiteToMove: Boolean, maxDepth: Int = 4): EngineResult {
        var rootMoves = generateLegalMoves(board, whiteToMove)
        // Если на доске в задаче нестандартная расстановка, никогда не зависаем без хода:
        if (rootMoves.isEmpty()) {
            rootMoves = generatePseudoMoves(board, whiteToMove)
        }
        if (rootMoves.isEmpty()) return EngineResult("", "", 0)

        var bestMove = rootMoves[0]
        var bestScore = if (whiteToMove) -999999 else 999999

        // Мгновенная проверка на мат в 1 ход
        for (m in rootMoves) {
            val undo = makeMove(board, m)
            val enemyLegal = generateLegalMoves(board, !whiteToMove)
            val givesCheck = isKingInCheck(board, !whiteToMove)
            undoMove(board, m, undo)

            if (enemyLegal.isEmpty() && givesCheck) {
                return EngineResult(m.toUci(), "", if (whiteToMove) 99000 else -99000)
            }
        }

        // Итеративное углубление с Quiescence Search (проверка всех цепочек взятий)
        for (d in 1..maxDepth) {
            var alpha = -1000000
            var beta = 1000000
            var curBest = bestMove
            var curScore = if (whiteToMove) -999999 else 999999

            val bestUci = bestMove.toUci()
            rootMoves.sortByDescending { if (it.toUci() == bestUci) 10000000 else it.score }

            for (m in rootMoves) {
                val undo = makeMove(board, m)
                val sc = alphaBeta(board, d - 1, alpha, beta, !whiteToMove)
                undoMove(board, m, undo)

                m.score = if (whiteToMove) sc else -sc

                if (whiteToMove) {
                    if (sc > curScore) { curScore = sc; curBest = m }
                    alpha = max(alpha, sc)
                } else {
                    if (sc < curScore) { curScore = sc; curBest = m }
                    beta = min(beta, sc)
                }
            }
            bestMove = curBest
            bestScore = curScore
            if (abs(bestScore) >= 85000) break
        }

        return EngineResult(bestMove.toUci(), "", bestScore)
    }

    private data class UndoState(
        val movedPiece: Char,
        val capturedPiece: Char,
        val rookFromC: Int = -1,
        val rookToC: Int = -1,
        val rookPiece: Char = '.'
    )

    private fun makeMove(board: Array<CharArray>, m: Move): UndoState {
        val mov = board[m.fromR][m.fromC]
        val cap = board[m.toR][m.toC]
        board[m.toR][m.toC] = if (m.promo != '.') m.promo else mov
        board[m.fromR][m.fromC] = '.'

        // Рокировка
        if ((mov == 'K' || mov == 'k') && abs(m.toC - m.fromC) == 2) {
            if (m.toC == 6) {
                val rP = board[m.toR][7]
                board[m.toR][5] = rP
                board[m.toR][7] = '.'
                return UndoState(mov, cap, 7, 5, rP)
            } else if (m.toC == 2) {
                val rP = board[m.toR][0]
                board[m.toR][3] = rP
                board[m.toR][0] = '.'
                return UndoState(mov, cap, 0, 3, rP)
            }
        }
        return UndoState(mov, cap)
    }

    private fun undoMove(board: Array<CharArray>, m: Move, u: UndoState) {
        board[m.fromR][m.fromC] = u.movedPiece
        board[m.toR][m.toC] = u.capturedPiece
        if (u.rookFromC != -1) {
            board[m.toR][u.rookFromC] = u.rookPiece
            board[m.toR][u.rookToC] = '.'
        }
    }

    private fun alphaBeta(
        board: Array<CharArray>,
        depth: Int,
        alphaInit: Int,
        betaInit: Int,
        whiteToMove: Boolean
    ): Int {
        val legal = generateLegalMoves(board, whiteToMove)
        if (legal.isEmpty()) {
            return if (isKingInCheck(board, whiteToMove)) {
                if (whiteToMove) -90000 - depth * 500 else 90000 + depth * 500
            } else 0
        }

        if (depth <= 0) {
            return quiescence(board, alphaInit, betaInit, whiteToMove, 3)
        }

        var alpha = alphaInit
        var beta = betaInit

        if (whiteToMove) {
            var v = -999999
            for (m in legal) {
                val u = makeMove(board, m)
                v = max(v, alphaBeta(board, depth - 1, alpha, beta, false))
                undoMove(board, m, u)
                alpha = max(alpha, v)
                if (alpha >= beta) break
            }
            return v
        } else {
            var v = 999999
            for (m in legal) {
                val u = makeMove(board, m)
                v = min(v, alphaBeta(board, depth - 1, alpha, beta, true))
                undoMove(board, m, u)
                beta = min(beta, v)
                if (beta <= alpha) break
            }
            return v
        }
    }

    // Доигрывание всех форсированных взятий, чтобы бот никогда не подставлял ферзя или фигуры под ответный удар
    private fun quiescence(
        board: Array<CharArray>,
        alphaInit: Int,
        betaInit: Int,
        whiteToMove: Boolean,
        qDepth: Int
    ): Int {
        val standPat = evaluate(board)
        if (qDepth <= 0) return standPat

        var alpha = alphaInit
        var beta = betaInit

        if (whiteToMove) {
            if (standPat >= beta) return beta
            if (standPat > alpha) alpha = standPat
        } else {
            if (standPat <= alpha) return alpha
            if (standPat < beta) beta = standPat
        }

        val captures = generateLegalCaptures(board, whiteToMove)
        if (captures.isEmpty()) return standPat

        if (whiteToMove) {
            var v = standPat
            for (m in captures) {
                val u = makeMove(board, m)
                val score = quiescence(board, alpha, beta, false, qDepth - 1)
                undoMove(board, m, u)
                if (score > v) v = score
                if (v > alpha) alpha = v
                if (alpha >= beta) break
            }
            return v
        } else {
            var v = standPat
            for (m in captures) {
                val u = makeMove(board, m)
                val score = quiescence(board, alpha, beta, true, qDepth - 1)
                undoMove(board, m, u)
                if (score < v) v = score
                if (v < beta) beta = v
                if (beta <= alpha) break
            }
            return v
        }
    }

    private fun evaluate(board: Array<CharArray>): Int {
        var score = 0
        for (r in 0..7) {
            for (c in 0..7) {
                val p = board[r][c]
                if (p == '.') continue
                val isW = p.isUpperCase()
                val pr = if (isW) r else 7 - r
                var v = pieceVal[p.code]

                when (p.lowercaseChar()) {
                    'p' -> v += pawnPst[pr][c]
                    'n' -> v += knightPst[pr][c]
                    'b' -> v += bishopPst[pr][c]
                    'r' -> {
                        if (pr == 1) v += 30
                        if (c == 3 || c == 4) v += 10
                    }
                    'q' -> v += (3 - abs(3 - c)) * 4 + (3 - abs(3 - r)) * 4
                }
                if (isW) score += v else score -= v
            }
        }
        return score
    }

    fun generateLegalMoves(board: Array<CharArray>, whiteToMove: Boolean): ArrayList<Move> {
        val pseudo = generatePseudoMoves(board, whiteToMove)
        val legal = ArrayList<Move>(pseudo.size)
        for (m in pseudo) {
            val u = makeMove(board, m)
            val inCheck = isKingInCheck(board, whiteToMove)
            undoMove(board, m, u)
            if (!inCheck) legal.add(m)
        }
        return legal
    }

    private fun generateLegalCaptures(board: Array<CharArray>, whiteToMove: Boolean): ArrayList<Move> {
        val pseudo = generatePseudoMoves(board, whiteToMove, capturesOnly = true)
        val legal = ArrayList<Move>(pseudo.size)
        for (m in pseudo) {
            val u = makeMove(board, m)
            val inCheck = isKingInCheck(board, whiteToMove)
            undoMove(board, m, u)
            if (!inCheck) legal.add(m)
        }
        return legal
    }

    fun isKingInCheck(board: Array<CharArray>, whiteKing: Boolean): Boolean {
        val kChar = if (whiteKing) 'K' else 'k'
        var kr = -1; var kc = -1
        for (r in 0..7) {
            for (c in 0..7) {
                if (board[r][c] == kChar) { kr = r; kc = c; break }
            }
            if (kr != -1) break
        }
        if (kr == -1) return false
        val enemyMoves = generatePseudoMoves(board, !whiteKing)
        for (i in 0 until enemyMoves.size) {
            val em = enemyMoves[i]
            if (em.toR == kr && em.toC == kc) return true
        }
        return false
    }

    fun generatePseudoMoves(
        board: Array<CharArray>,
        whiteToMove: Boolean,
        capturesOnly: Boolean = false
    ): ArrayList<Move> {
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
                            if (!capturesOnly && board[nr][c] == '.') {
                                val pr = if (nr == promoRank) promoChar else '.'
                                moves.add(Move(r, c, nr, c, pr, if (pr != '.') 900 else 12))
                                val nnr = r + dir * 2
                                if (r == startRank && nnr in 0..7 && board[nnr][c] == '.') {
                                    moves.add(Move(r, c, nnr, c, '.', 18))
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
                                if (t == '.') {
                                    if (!capturesOnly) moves.add(Move(r, c, nr, nc, '.', 22))
                                } else if (t.isUpperCase() != whiteToMove) {
                                    moves.add(Move(r, c, nr, nc, '.', pieceVal[t.code] * 10 - 320))
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
                                    if (t == '.') {
                                        if (!capturesOnly) moves.add(Move(r, c, nr, nc, '.', 5))
                                    } else if (t.isUpperCase() != whiteToMove) {
                                        moves.add(Move(r, c, nr, nc, '.', pieceVal[t.code] * 10 - 100))
                                    }
                                }
                            }
                        }
                        // Короткая и длинная рокировка
                        if (!capturesOnly) {
                            val homeR = if (whiteToMove) 7 else 0
                            val rChar = if (whiteToMove) 'R' else 'r'
                            if (r == homeR && c == 4) {
                                if (board[homeR][5] == '.' && board[homeR][6] == '.' && board[homeR][7] == rChar) {
                                    moves.add(Move(homeR, 4, homeR, 6, '.', 40))
                                }
                                if (board[homeR][3] == '.' && board[homeR][2] == '.' && board[homeR][1] == '.' && board[homeR][0] == rChar) {
                                    moves.add(Move(homeR, 4, homeR, 2, '.', 35))
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
                                    if (!capturesOnly) moves.add(Move(r, c, nr, nc, '.', 14))
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
