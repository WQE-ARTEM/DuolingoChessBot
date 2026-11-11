package com.chessbot.assistant

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.math.min

class ChessOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var boardOverlay: BoardOverlayView? = null
    private var controlPanel: LinearLayout? = null
    private var statusText: TextView? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var chessEngine: ChessEngine
    private lateinit var autoMoveService: AutoMoveService

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private var autoPlayEnabled = false
    private var isManualEditing = false
    private var selectedSquareForMove: Pair<Int, Int>? = null

    private var internalBoard = arrayOf(
        charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R'),
        charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
        charArrayOf('r', 'n', 'b', 'k', 'q', 'b', 'n', 'r')
    )

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post { stopCaptureResources() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = windowManager.currentWindowMetrics.bounds
        val metrics = resources.displayMetrics
        screenWidth = (bounds.width() / 2) * 2
        screenHeight = (bounds.height() / 2) * 2
        screenDensity = metrics.densityDpi

        chessEngine = ChessEngine(this)
        autoMoveService = AutoMoveService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundWithNotification()
            if (Settings.canDrawOverlays(this) && boardOverlay == null) {
                setupOverlays()
            }

            val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA_INTENT", Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra("DATA_INTENT")
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                mediaProjection?.registerCallback(projectionCallback, mainHandler)
                setupVirtualDisplay()
            }
        } catch (_: Throwable) {}
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "chessbot_overlay_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "ChessBot", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Chess Bot Активен")
            .setContentText("Интерактивная доска и Stockfish")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(101, notification)
        }
    }

    private fun setupOverlays() {
        val overlayView = BoardOverlayView(this).apply {
            boardLeftPx = 0f
            boardTopPx = screenHeight * 0.35f
            boardSizePx = screenWidth.toFloat()
            whiteAtBottom = false
            detectedBoard = internalBoard

            onSquareSelectedListener = { f, r ->
                handleManualSquareClick(f, r)
            }
        }
        boardOverlay = overlayView

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager.addView(overlayView, overlayParams)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(230, 25, 35, 40))
            setPadding(12, 8, 12, 8)
            gravity = Gravity.CENTER
        }
        controlPanel = panel

        statusText = TextView(this).apply {
            text = "Готов. Сделай 1-й ход"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(8, 0, 16, 0)
        }

        val btnManual = Button(this).apply {
            text = "✋ Ручн."
            textSize = 12f
            setPadding(12, 4, 12, 4)
            setOnClickListener {
                isManualEditing = !isManualEditing
                boardOverlay?.isManualMode = isManualEditing
                text = if (isManualEditing) "✏️ Ред." else "✋ Ручн."
                statusText?.text = if (isManualEditing) "Режим расстановки: тапни откуда и куда" else "Режим игры"
                boardOverlay?.invalidate()
            }
        }

        val btnReset = Button(this).apply {
            text = "🔄 Сброс"
            textSize = 12f
            setPadding(12, 4, 12, 4)
            setOnClickListener {
                internalBoard = arrayOf(
                    charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R'),
                    charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
                    charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                    charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                    charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                    charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                    charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
                    charArrayOf('r', 'n', 'b', 'k', 'q', 'b', 'n', 'r')
                )
                boardOverlay?.detectedBoard = internalBoard
                boardOverlay?.bestMoveUci = null
                boardOverlay?.invalidate()
                statusText?.text = "Доска сброшена. Сходи первый!"
            }
        }

        val btnAuto = Button(this).apply {
            text = "Авто: ВЫКЛ"
            textSize = 12f
            setPadding(12, 4, 12, 4)
            setOnClickListener {
                autoPlayEnabled = !autoPlayEnabled
                text = if (autoPlayEnabled) "Авто: ВКЛ" else "Авто: ВЫКЛ"
            }
        }

        panel.addView(statusText)
        panel.addView(btnManual)
        panel.addView(btnReset)
        panel.addView(btnAuto)

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; y = 100 }

        windowManager.addView(panel, panelParams)
    }

    private fun handleManualSquareClick(file: Int, rank: Int) {
        val r = 7 - rank
        val c = file

        if (selectedSquareForMove == null) {
            if (internalBoard[r][c] != '.') {
                selectedSquareForMove = r to c
                boardOverlay?.selectedSquare = Pair(file, rank)
                boardOverlay?.invalidate()
                statusText?.text = "Выбрано $file$rank. Куда пойти?"
            }
        } else {
            val (fromR, fromC) = selectedSquareForMove!!
            val piece = internalBoard[fromR][fromC]
            internalBoard[fromR][fromC] = '.'
            internalBoard[r][c] = piece
            selectedSquareForMove = null
            boardOverlay?.selectedSquare = null
            boardOverlay?.detectedBoard = internalBoard
            boardOverlay?.invalidate()

            val fromFileChar = ('a' + fromC)
            val fromRankChar = ('1' + (7 - fromR))
            val toFileChar = ('a' + c)
            val toRankChar = ('1' + (7 - r))
            val uci = "$fromFileChar$fromRankChar$toFileChar$toRankChar"

            statusText?.text = "Ход $uci сделан. Дублирую в игру..."
            serviceScope.launch {
                val left = boardOverlay?.boardLeftPx ?: 0f
                val top = boardOverlay?.boardTopPx ?: 0f
                val size = boardOverlay?.boardSizePx ?: screenWidth.toFloat()
                
                setAutoClickable(false)
                autoMoveService.performChessMove(uci, left, top, size, whiteAtBottom = false) {
                    setAutoClickable(true)
                    statusText?.text = "⏳ Жду ответ соперника..."
                    calculateBestMove()
                }
            }
        }
    }

    private fun setAutoClickable(clickable: Boolean) {
        val params = controlPanel?.layoutParams as? WindowManager.LayoutParams ?: return
        if (clickable) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
        windowManager.updateViewLayout(controlPanel, params)
    }

    private fun calculateBestMove() {
        serviceScope.launch(Dispatchers.Default) {
            val fen = buildFen(internalBoard, whiteAtBottom = false)
            val bestMove = chessEngine.getBestMove(fen, movetimeMs = 500)
            withContext(Dispatchers.Main) {
                if (bestMove != null && bestMove.length >= 4) {
                    boardOverlay?.bestMoveUci = bestMove
                    boardOverlay?.invalidate()
                    statusText?.text = "Ход бота: $bestMove"

                    if (autoPlayEnabled) {
                        executeBotMove(bestMove)
                    }
                }
            }
        }
    }

    private fun executeBotMove(uci: String) {
        val fromFile = uci[0] - 'a'
        val fromRank = uci[1] - '1'
        val toFile = uci[2] - 'a'
        val toRank = uci[3] - '1'

        val fromR = 7 - fromRank
        val fromC = fromFile
        val toR = 7 - toRank
        val toC = toFile

        val piece = internalBoard[fromR][fromC]
        internalBoard[fromR][fromC] = '.'
        internalBoard[toR][toC] = piece
        boardOverlay?.detectedBoard = internalBoard
        boardOverlay?.bestMoveUci = null
        boardOverlay?.invalidate()

        serviceScope.launch {
            val left = boardOverlay?.boardLeftPx ?: 0f
            val top = boardOverlay?.boardTopPx ?: 0f
            val size = boardOverlay?.boardSizePx ?: screenWidth.toFloat()

            setAutoClickable(false)
            autoMoveService.performChessMove(uci, left, top, size, whiteAtBottom = false) {
                setAutoClickable(true)
                statusText?.text = "Твой ход! Сдвинь фигуру на доске."
            }
        }
    }

    private fun buildFen(board: Array<CharArray>, whiteAtBottom: Boolean): String {
        val sb = StringBuilder()
        val rows = (7 downTo 0)
        val cols = (7 downTo 0)
        var firstRow = true
        for (r in rows) {
            if (!firstRow) sb.append('/')
            firstRow = false
            var empty = 0
            for (c in cols) {
                val p = board[r][c]
                if (p == '.') empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(p)
                }
            }
            if (empty > 0) sb.append(empty)
        }
        sb.append(" b KQkq - 0 1")
        return sb.toString()
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessBotScreen",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun stopCaptureResources() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        stopCaptureResources()
        boardOverlay?.let { windowManager.removeView(it) }
        controlPanel?.let { windowManager.removeView(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
