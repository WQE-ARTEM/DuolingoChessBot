package com.chessbot.assistant

import android.graphics.Bitmap

object BoardRecognizer {
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
        return ScanResult(
            board = Array(8) { CharArray(8) { '.' } },
            fen = lockedFenState.ifEmpty { "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1" },
            isValid = true,
            statusNote = "Ручной режим",
            continueButtonActive = false,
            detectedWhiteAtBottom = whiteAtBottom
        )
    }
}
