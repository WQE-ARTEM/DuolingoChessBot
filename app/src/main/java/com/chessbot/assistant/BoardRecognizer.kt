package com.chessbot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

object BoardRecognizer {

    // Стандартная начальная FEN для игры за черных снизу
    private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1"

    data class ScanResult(
        val board: Array<CharArray>,
        val fen: String,
        val isValid: Boolean,
        val statusNote: String,
        val continueButtonActive: Boolean = false,
        val detectedWhiteAtBottom: Boolean = false
    )

    fun isFrameStable(prev: Bitmap?, curr: Bitmap, topPx: Int, sizePx: Int): Boolean = true

    fun recognizeBoard(
        bitmap: Bitmap,
        boardLeftPx: Int,
        boardTopPx: Int,
        boardSizePx: Int,
        whiteAtBottom: Boolean = false,
        lockedFenState: String = ""
    ): ScanResult {
        val continueActive = detectContinueButton(bitmap)

        // Превращаем FEN в матрицу 8x8 для оверлея
        val board = Array(8) { CharArray(8) { '.' } }
        val fenParts = START_FEN.split(" ")[0].split("/")
        for (r in 0..7) {
            var c = 0
            for (ch in fenParts[r]) {
                if (ch.isDigit()) {
                    c += ch.digitToInt()
                } else if (c < 8) {
                    board[r][c++] = ch
                }
            }
        }

        return ScanResult(
            board = board,
            fen = START_FEN,
            isValid = true,
            statusNote = "Stockfish Direct",
            continueButtonActive = continueActive,
            detectedWhiteAtBottom = false
        )
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
                if (g > 170 && g > r + 55) green++
                total++; x += 15
            }
            y += 10
        }
        return total > 0 && (green.toFloat() / total) > 0.35f
    }
}
