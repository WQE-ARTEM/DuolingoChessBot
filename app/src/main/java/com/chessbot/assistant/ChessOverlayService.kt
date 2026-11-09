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
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChessOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var boardOverlay: BoardOverlayView
    private lateinit var controlPanel: LinearLayout
    private lateinit var statusText: TextView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var chessEngine: ChessEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private var autoPlayEnabled = false
    private var isExecutingMove = false
    private var lastAutoPlayedFen = ""
    private var previousFrameBitmap: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        chessEngine = ChessEngine(this)
        setupOverlays()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA_INTENT", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA_INTENT")
        }

        startForegroundWithNotification()

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
            startScanningLoop()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "chessbot_overlay_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channelId, "ChessBot Service", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Duolingo Chess Bot активен")
            .setContentText("Анализ доски и визуализация ходов")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(101, notification)
        }
    }

    private fun setupOverlays() {
        boardOverlay = BoardOverlayView(this).apply {
            boardLeftPx = 0f
            boardTopPx = screenHeight * 0.3585f
            boardSizePx = screenWidth.toFloat()
        }
        val boardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(boardOverlay, boardParams)

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = (screenHeight * 0.08f).toInt()
        }

        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(225, 22, 32, 38))
            setPadding(20, 14, 20, 14)
        }

        statusText = TextView(this).apply {
            text = "♟️ ChessBot готов | Перетащите панель"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        controlPanel.addView(statusText)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnAuto = createCompactButton("🤖 Авто: ВЫКЛ").apply {
            setOnClickListener {
                autoPlayEnabled = !autoPlayEnabled
                text = if (autoPlayEnabled) "🤖 Авто: ВКЛ" else "🤖 Авто: ВЫКЛ"
                setBackgroundColor(if (autoPlayEnabled) Color.rgb(40, 140, 60) else Color.rgb(55, 70, 80))
            }
        }
        val btnOneMove = createCompactButton("⚡ 1 Ход").apply {
            setOnClickListener {
                val move = boardOverlay.bestMoveUci
                if (!move.isNullOrEmpty() && !isExecutingMove) executeAutoMove(move)
            }
        }
        val btnSide = createCompactButton("🔄 Белые").apply {
            setOnClickListener {
                boardOverlay.whiteAtBottom = !boardOverlay.whiteAtBottom
                text = if (boardOverlay.whiteAtBottom) "🔄 Белые" else "🔄 Черные"
                lastAutoPlayedFen = ""
                boardOverlay.invalidate()
            }
        }
        val btnClose = createCompactButton("✖").apply {
            setBackgroundColor(Color.rgb(150, 45, 45))
            setOnClickListener { stopSelf() }
        }
        row1.addView(btnAuto); row1.addView(btnOneMove); row1.addView(btnSide); row1.addView(btnClose)
        controlPanel.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnGrid = createCompactButton("🎯 Сетка").apply {
            setOnClickListener {
                boardOverlay.showCalibrationGrid = !boardOverlay.showCalibrationGrid
                boardOverlay.invalidate()
            }
        }
        val btnUp = createCompactButton("▲ Y-").apply {
            setOnClickListener { boardOverlay.boardTopPx -= 6f; boardOverlay.invalidate() }
        }
        val btnDown = createCompactButton("▼ Y+").apply {
            setOnClickListener { boardOverlay.boardTopPx += 6f; boardOverlay.invalidate() }
        }
        row2.addView(btnGrid); row2.addView(btnUp); row2.addView(btnDown)
        controlPanel.addView(row2)

        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
        statusText.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = panelParams.x; initY = panelParams.y
                    touchX = event.rawX; touchY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = initX + (event.rawX - touchX).toInt()
                    panelParams.y = initY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(controlPanel, panelParams); true
                }
                else -> false
            }
        }
        windowManager.addView(controlPanel, panelParams)
    }

    private fun createCompactButton(label: String): Button {
        return Button(this).apply {
            text = label; textSize = 10.5f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(55, 70, 80)); setPadding(14, 6, 14, 6)
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        }
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessBotCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun startScanningLoop() {
        scanJob = serviceScope.launch {
            while (isActive) {
                if (isExecutingMove) { delay(350); continue }
                boardOverlay.visibility = View.INVISIBLE
                delay(65)
                val frame = captureScreenBitmap()
                boardOverlay.visibility = View.VISIBLE

                if (frame != null) {
                    val topPx = boardOverlay.boardTopPx.toInt()
                    val sizePx = boardOverlay.boardSizePx.toInt()
                    val leftPx = boardOverlay.boardLeftPx.toInt()
                    val whiteBottom = boardOverlay.whiteAtBottom

                    val stable = BoardRecognizer.isFrameStable(previousFrameBitmap, frame, topPx, sizePx)
                    previousFrameBitmap = frame
                    if (!stable) { statusText.text = "⏳ Ожидание завершения анимации..."; delay(260); continue }

                    val scan = withContext(Dispatchers.Default) {
                        BoardRecognizer.recognizeBoard(frame, leftPx, topPx, sizePx, whiteBottom)
                    }
                    boardOverlay.detectedBoard = scan.board

                    if (scan.continueButtonActive && autoPlayEnabled) {
                        statusText.text = "✅ Задача решена! Нажимаем ПРОДОЛЖИТЬ..."
                        isExecutingMove = true
                        boardOverlay.visibility = View.INVISIBLE
                        AutoMoveService.instance?.tapContinueButton(screenWidth, screenHeight) {
                            boardOverlay.visibility = View.VISIBLE
                            isExecutingMove = false
                            lastAutoPlayedFen = ""
                        }
                        delay(700); continue
                    }

                    if (scan.isValid) {
                        val analysis = withContext(Dispatchers.Default) {
                            chessEngine.analyzePosition(scan.fen, 380)
                        }
                        boardOverlay.bestMoveUci = analysis.bestMoveUci
                        boardOverlay.ponderMoveUci = analysis.ponderMoveUci
                        boardOverlay.evalScoreCp = analysis.evalScoreCp
                        boardOverlay.invalidate()

                        val evalFormatted = String.format("%+.1f", analysis.evalScoreCp / 100f)
                        statusText.text = "♟️ Ход: ${analysis.bestMoveUci} ($evalFormatted) | ${analysis.engineName}"

                        if (autoPlayEnabled && scan.fen != lastAutoPlayedFen) {
                            lastAutoPlayedFen = scan.fen
                            executeAutoMove(analysis.bestMoveUci)
                        }
                    } else {
                        statusText.text = "🔍 ${scan.statusNote}"
                        boardOverlay.invalidate()
                    }
                }
                delay(650)
            }
        }
    }

    private fun executeAutoMove(uciMove: String) {
        val accService = AutoMoveService.instance
        if (accService == null) {
            statusText.text = "⚠️ Включите Спец. возможности (ChessBot Auto-Mover)!"
            return
        }
        isExecutingMove = true
        boardOverlay.visibility = View.GONE
        accService.performChessMove(
            uciMove = uciMove,
            boardLeft = boardOverlay.boardLeftPx,
            boardTop = boardOverlay.boardTopPx,
            boardSize = boardOverlay.boardSizePx,
            whiteAtBottom = boardOverlay.whiteAtBottom
        ) {
            boardOverlay.visibility = View.VISIBLE
            isExecutingMove = false
        }
    }

    private fun captureScreenBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val rawBitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
            rawBitmap.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
        } catch (e: Exception) { null } finally { image.close() }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        serviceScope.cancel()
        chessEngine.close()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        try { windowManager.removeView(boardOverlay); windowManager.removeView(controlPanel) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
