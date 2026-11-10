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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val errorHandler = CoroutineExceptionHandler { _, throwable ->
        mainHandler.post {
            statusText?.text = "⚠️ Ошибка цикла: ${throwable.javaClass.simpleName}"
        }
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + errorHandler)
    private var scanJob: Job? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private var autoPlayEnabled = false
    private var isExecutingMove = false
    private var lastAutoPlayedFen = ""
    private var previousFrameBitmap: Bitmap? = null

    // Обязательный колбэк для Android 14 (API 34), без которого приложение падает
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                stopCaptureResources()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = (metrics.widthPixels / 2) * 2
        screenHeight = (metrics.heightPixels / 2) * 2
        screenDensity = metrics.densityDpi

        chessEngine = ChessEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundWithNotification()

            if (Settings.canDrawOverlays(this) && boardOverlay == null) {
                setupOverlays()
            }

            val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA_INTENT", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("DATA_INTENT")
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, data)

                // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ ДЛЯ ANDROID 14 (API 34):
                // Регистрация колбэка строго ДО вызова createVirtualDisplay
                mediaProjection?.registerCallback(projectionCallback, mainHandler)

                setupVirtualDisplay()
                startScanningLoop()
            } else {
                statusText?.text = "⚠️ Нет доступа к захвату экрана"
            }
        } catch (t: Throwable) {
            statusText?.text = "⚠️ Ошибка запуска: ${t.message}"
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "chessbot_overlay_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "ChessBot Service",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Duolingo Chess Bot активен")
            .setContentText("Анализ доски и визуализация ходов в реальном времени")
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
            boardTopPx = screenHeight * 0.3585f
            boardSizePx = screenWidth.toFloat()
        }
        boardOverlay = overlayView

        val boardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlayView, boardParams)

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

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 22, 32, 38))
            setPadding(20, 14, 20, 14)
        }
        controlPanel = panel

        val stText = TextView(this).apply {
            text = "♟️ ChessBot готов | Перетащите панель"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        statusText = stText
        panel.addView(stText)

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
                val move = boardOverlay?.bestMoveUci
                if (!move.isNullOrEmpty() && !isExecutingMove) {
                    executeAutoMove(move)
                }
            }
        }

        val btnSide = createCompactButton("🔄 Белые").apply {
            setOnClickListener {
                val current = boardOverlay ?: return@setOnClickListener
                current.whiteAtBottom = !current.whiteAtBottom
                text = if (current.whiteAtBottom) "🔄 Белые" else "🔄 Черные"
                lastAutoPlayedFen = ""
                current.invalidate()
            }
        }

        val btnClose = createCompactButton("✖").apply {
            setBackgroundColor(Color.rgb(150, 45, 45))
            setOnClickListener { stopSelf() }
        }

        row1.addView(btnAuto)
        row1.addView(btnOneMove)
        row1.addView(btnSide)
        row1.addView(btnClose)
        panel.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val btnGrid = createCompactButton("🎯 Сетка").apply {
            setOnClickListener {
                val current = boardOverlay ?: return@setOnClickListener
                current.showCalibrationGrid = !current.showCalibrationGrid
                current.invalidate()
            }
        }
        val btnUp = createCompactButton("▲ Y-").apply {
            setOnClickListener {
                val current = boardOverlay ?: return@setOnClickListener
                current.boardTopPx -= 6f
                current.invalidate()
            }
        }
        val btnDown = createCompactButton("▼ Y+").apply {
            setOnClickListener {
                val current = boardOverlay ?: return@setOnClickListener
                current.boardTopPx += 6f
                current.invalidate()
            }
        }

        row2.addView(btnGrid)
        row2.addView(btnUp)
        row2.addView(btnDown)
        panel.addView(row2)

        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
        stText.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = panelParams.x; initY = panelParams.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = initX + (event.rawX - touchX).toInt()
                    panelParams.y = initY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(panel, panelParams)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(panel, panelParams)
    }

    private fun createCompactButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 10.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(55, 70, 80))
            setPadding(14, 6, 14, 6)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
        }
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessBotCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    private fun startScanningLoop() {
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            delay(400)
            while (isActive) {
                val overlay = boardOverlay
                if (overlay == null || isExecutingMove) {
                    delay(350)
                    continue
                }

                overlay.visibility = View.INVISIBLE
                delay(70)

                val frame = captureScreenBitmap()

                overlay.visibility = View.VISIBLE

                if (frame != null) {
                    val topPx = overlay.boardTopPx.toInt().coerceIn(0, frame.height - 100)
                    val sizePx = min(overlay.boardSizePx.toInt(), frame.height - topPx)
                    val leftPx = overlay.boardLeftPx.toInt().coerceIn(0, frame.width - 100)
                    val whiteBottom = overlay.whiteAtBottom

                    val stable = BoardRecognizer.isFrameStable(previousFrameBitmap, frame, topPx, sizePx)
                    previousFrameBitmap = frame

                    if (!stable) {
                        statusText?.text = "⏳ Ожидание завершения анимации..."
                        delay(260)
                        continue
                    }

                    val scan = withContext(Dispatchers.Default) {
                        BoardRecognizer.recognizeBoard(frame, leftPx, topPx, sizePx, whiteBottom)
                    }

                    overlay.detectedBoard = scan.board

                    if (scan.continueButtonActive && autoPlayEnabled) {
                        statusText?.text = "✅ Задача решена! Нажимаем ПРОДОЛЖИТЬ..."
                        isExecutingMove = true
                        overlay.visibility = View.INVISIBLE
                        AutoMoveService.instance?.tapContinueButton(screenWidth, screenHeight) {
                            overlay.visibility = View.VISIBLE
                            isExecutingMove = false
                            lastAutoPlayedFen = ""
                        }
                        delay(700)
                        continue
                    }

                    if (scan.isValid) {
                        val analysis = withContext(Dispatchers.Default) {
                            chessEngine.analyzePosition(scan.fen, 380)
                        }
                        overlay.bestMoveUci = analysis.bestMoveUci
                        overlay.ponderMoveUci = analysis.ponderMoveUci
                        overlay.evalScoreCp = analysis.evalScoreCp
                        overlay.invalidate()

                        val evalFormatted = String.format("%+.1f", analysis.evalScoreCp / 100f)
                        statusText?.text = "♟️ Ход: ${analysis.bestMoveUci} ($evalFormatted) | ${analysis.engineName}"

                        if (autoPlayEnabled && scan.fen != lastAutoPlayedFen) {
                            lastAutoPlayedFen = scan.fen
                            executeAutoMove(analysis.bestMoveUci)
                        }
                    } else {
                        statusText?.text = "🔍 ${scan.statusNote}"
                        overlay.invalidate()
                    }
                }

                delay(650)
            }
        }
    }

    private fun executeAutoMove(uciMove: String) {
        val overlay = boardOverlay ?: return
        val accService = AutoMoveService.instance
        if (accService == null) {
            statusText?.text = "⚠️ Включите Accessibility (ChessBot Auto-Mover)!"
            return
        }

        isExecutingMove = true
        overlay.visibility = View.GONE

        accService.performChessMove(
            uciMove = uciMove,
            boardLeft = overlay.boardLeftPx,
            boardTop = overlay.boardTopPx,
            boardSize = overlay.boardSizePx,
            whiteAtBottom = overlay.whiteAtBottom
        ) {
            overlay.visibility = View.VISIBLE
            isExecutingMove = false
        }
    }

    /**
     * Безопасное чтение кадра из ImageReader с защитой от BufferUnderflowException
     * при наличии аппаратного отступа строк (rowPadding).
     */
    private fun captureScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val strideWidth = screenWidth + rowPadding / pixelStride

            // Вычисляем безопасную высоту по реальному объему буфера, чтобы избежать BufferUnderflowException
            val availableRows = buffer.remaining() / rowStride
            val safeHeight = min(screenHeight, availableRows)
            if (safeHeight <= 0 || strideWidth <= 0) return null

            val rawBitmap = Bitmap.createBitmap(strideWidth, safeHeight, Bitmap.Config.ARGB_8888)
            rawBitmap.copyPixelsFromBuffer(buffer)

            if (strideWidth == screenWidth && safeHeight == screenHeight) {
                rawBitmap
            } else {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, safeHeight)
                rawBitmap.recycle()
                cropped
            }
        } catch (t: Throwable) {
            null
        } finally {
            try {
                image.close()
            } catch (_: Throwable) {}
        }
    }

    private fun stopCaptureResources() {
        scanJob?.cancel()
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
    }

    override fun onDestroy() {
        stopCaptureResources()
        serviceScope.cancel()
        chessEngine.close()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Throwable) {}
        try {
            boardOverlay?.let { windowManager.removeView(it) }
            controlPanel?.let { windowManager.removeView(it) }
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
