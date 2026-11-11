package com.chessbot.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlin.random.Random

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
        instance = null
        super.onDestroy()
    }

    fun performChessMove(
        uciMove: String,
        boardLeft: Float,
        boardTop: Float,
        boardSize: Float,
        whiteAtBottom: Boolean,
        onComplete: () -> Unit
    ) {
        if (uciMove.length < 4) {
            onComplete()
            return
        }

        val cellSize = boardSize / 8f
        val fromFile = uciMove[0] - 'a'
        val fromRank = uciMove[1] - '1'
        val toFile = uciMove[2] - 'a'
        val toRank = uciMove[3] - '1'

        val startCol = if (whiteAtBottom) fromFile else 7 - fromFile
        val startRow = if (whiteAtBottom) 7 - fromRank else fromRank
        val endCol = if (whiteAtBottom) toFile else 7 - toFile
        val endRow = if (whiteAtBottom) 7 - toRank else toRank

        val jitterX1 = Random.nextInt(-5, 6).toFloat()
        val jitterY1 = Random.nextInt(-5, 6).toFloat()
        val jitterX2 = Random.nextInt(-5, 6).toFloat()
        val jitterY2 = Random.nextInt(-5, 6).toFloat()

        val startX = (boardLeft + (startCol + 0.5f) * cellSize + jitterX1).coerceAtLeast(1f)
        val startY = (boardTop + (startRow + 0.5f) * cellSize + jitterY1).coerceAtLeast(1f)
        val endX = (boardLeft + (endCol + 0.5f) * cellSize + jitterX2).coerceAtLeast(1f)
        val endY = (boardTop + (endRow + 0.5f) * cellSize + jitterY2).coerceAtLeast(1f)

        dispatchTap(startX, startY, 65L) {
            handler.postDelayed({
                dispatchTap(endX, endY, 65L) {
                    if (uciMove.length == 5) {
                        handler.postDelayed({
                            dispatchTap(endX, endY, 65L) { onComplete() }
                        }, 320L)
                    } else {
                        onComplete()
                    }
                }
            }, 170L)
        }
    }

    fun tapContinueButton(screenWidth: Int, screenHeight: Int, onComplete: () -> Unit) {
        val x = screenWidth * 0.60f + Random.nextInt(-10, 11)
        val y = screenHeight * 0.91f + Random.nextInt(-6, 7)
        dispatchTap(x, y, 70L, onComplete)
    }

    private fun dispatchTap(x: Float, y: Float, durationMs: Long, onDone: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone() }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone() }
        }, null)
        if (!dispatched) onDone()
    }
}
