package com.chessbot.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class AutoMoveService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoMoveService? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun performChessMove(
        uciMove: String,
        boardLeft: Float,
        boardTop: Float,
        boardSize: Float,
        whiteAtBottom: Boolean,
        onComplete: (() -> Unit)? = null
    ) {
        if (uciMove.length < 4) {
            onComplete?.invoke()
            return
        }

        val cellSize = boardSize / 8f
        val fromFile = uciMove[0] - 'a'
        val fromRank = uciMove[1] - '1'
        val toFile = uciMove[2] - 'a'
        val toRank = uciMove[3] - '1'

        val sc1 = if (whiteAtBottom) fromFile else 7 - fromFile
        val sr1 = if (whiteAtBottom) 7 - fromRank else fromRank
        val sc2 = if (whiteAtBottom) toFile else 7 - toFile
        val sr2 = if (whiteAtBottom) 7 - toRank else toRank

        val x1 = boardLeft + (sc1 + 0.5f) * cellSize
        val y1 = boardTop + (sr1 + 0.5f) * cellSize
        val x2 = boardLeft + (sc2 + 0.5f) * cellSize
        val y2 = boardTop + (sr2 + 0.5f) * cellSize

        // 1-й тап: выбираем фигуру
        dispatchTap(x1, y1, 45L) {
            // 2-й тап через 115 мс: ставим фигуру на целевую клетку
            handler.postDelayed({
                dispatchTap(x2, y2, 45L) {
                    // Если это превращение пешки (5 символов, например e7e8q) — тапаем ещё раз для выбора Ферзя
                    if (uciMove.length >= 5) {
                        handler.postDelayed({
                            dispatchTap(x2, y2, 45L) { onComplete?.invoke() }
                        }, 240L)
                    } else {
                        onComplete?.invoke()
                    }
                }
            }, 115L)
        }
    }

    private fun dispatchTap(x: Float, y: Float, durationMs: Long, onDone: () -> Unit) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.post { onDone() }
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    handler.post { onDone() }
                }
            }, null)
        } catch (_: Throwable) {
            onDone()
        }
    }
}
