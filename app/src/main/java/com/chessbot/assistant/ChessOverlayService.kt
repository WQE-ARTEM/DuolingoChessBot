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
import android.os.HandlerThread
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
import kotlinx.coroutines.*
import kotlin.math.abs

class ChessOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var boardOverlay: BoardOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var controlPanel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var miniButton: Button? = null
    private var statusText: TextView? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    @Volatile
    private var latestFrameBitmap: Bitmap? = null
    private lateinit var chessEngine: ChessEngine

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcherJob: Job? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private var isChessComMode = false
    private var playerIsWhite = true
    private var autoPlayEnabled = false
    private var isEditCellsMode = false
    private var isMinimized = false
    private var boardTopY = 780

    private var currentBestMoveUci: String = ""
    private var isExecutingGesture = false

    private var internalBoard = createInitialBoard()

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
        boardTopY = (screenHeight * 0.326f).toInt()
        chessEngine = ChessEngine(this)

        captureThread = HandlerThread("ScreenCaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val mode = intent?.getStringExtra("GAME_MODE") ?: "DUOLINGO"
            isChessComMode = (mode == "CHESS_COM")
            boardTopY = if (isChessComMode) {
                (screenHeight * 0.324f).toInt()
            } else {
                (screenHeight * 0.330f).toInt()
            }

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
                startScreenSyncLoop()
                mainHandler.postDelayed({ forceScanFromScreenNow() }, 600L)
            }
        } catch (t: Throwable) {
            statusText?.text = "Ошибка: ${t.message}"
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "chessbot_overlay_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "ChessBot", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
        val modeName = if (isChessComMode) "Chess.com" else "Duolingo"
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Chess Bot ($modeName)")
            .setContentText("Защита от анимаций съедания активна")
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
        val boardSize = screenWidth

        val overlayView = BoardOverlayView(this).apply {
            boardLeftPx = 0f
            boardTopPx = boardTopY.toFloat()
            boardSizePx = boardSize.toFloat()
            whiteAtBottom = playerIsWhite
            detectedBoard = internalBoard
            touchInteractive = false
            onBoardCellClick = { r, c -> onCellEditTapped(r, c) }
        }
        boardOverlay = overlayView

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val bParams = WindowManager.LayoutParams(
            boardSize,
            boardSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = boardTopY
        }
        overlayParams = bParams
        windowManager.addView(overlayView, bParams)

        val rootPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(238, 18, 25, 34))
            setPadding(6, 4, 6, 4)
        }
        controlPanel = rootPanel

        val modeTitle = if (isChessComMode) "♟️ Chess.com" else "🦉 Duolingo"
        statusText = TextView(this).apply {
            text = "$modeTitle | Жми [🔍 Скан] после анимации хода"
            setTextColor(Color.rgb(110, 255, 160))
            textSize = 11f
            setPadding(4, 2, 4, 3)
        }
        rootPanel.addView(statusText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun makeBtn(label: String, bg: Int, weight: Float = 1f): Button {
            return Button(this).apply {
                text = label
                textSize = 10f
                setTextColor(Color.WHITE)
                setBackgroundColor(bg)
                setPadding(2, 2, 2, 2)
                val lp = LinearLayout.LayoutParams(0, 88, weight)
                lp.setMargins(1, 1, 1, 1)
                layoutParams = lp
            }
        }

        val btnScan = makeBtn("🔍 Скан", Color.rgb(20, 125, 155), 1.1f).apply {
            setOnClickListener {
                if (isEditCellsMode) {
                    setEditMode(false)
                    runEngineSuggestion()
                } else {
                    forceScanFromScreenNow()
                }
            }
            setOnLongClickListener {
                setEditMode(!isEditCellsMode)
                true
            }
        }

        val btnMove = makeBtn("▶️ Ход", Color.rgb(35, 145, 75), 1.0f).apply {
            setOnClickListener {
                if (currentBestMoveUci.length >= 4) {
                    executeMoveOnBothBoards(currentBestMoveUci)
                } else {
                    forceScanFromScreenNow()
                }
            }
        }

        val btnAuto = makeBtn("🤖 ВЫКЛ", Color.rgb(45, 62, 78), 1.0f).apply {
            setOnClickListener {
                autoPlayEnabled = !autoPlayEnabled
                text = if (autoPlayEnabled) "🤖 ВКЛ" else "🤖 ВЫКЛ"
                setBackgroundColor(if (autoPlayEnabled) Color.rgb(30, 135, 70) else Color.rgb(45, 62, 78))
                if (autoPlayEnabled && currentBestMoveUci.length >= 4) executeMoveOnBothBoards(currentBestMoveUci)
            }
        }

        val btnSide = makeBtn(if (playerIsWhite) "Бел" else "Чер", Color.rgb(55, 72, 90), 0.75f).apply {
            setOnClickListener {
                playerIsWhite = !playerIsWhite
                boardOverlay?.whiteAtBottom = playerIsWhite
                text = if (playerIsWhite) "Бел" else "Чер"
                forceScanFromScreenNow()
            }
        }

        val btnReset = makeBtn("🔄 Новая", Color.rgb(105, 65, 145), 0.9f).apply {
            setOnClickListener {
                internalBoard = createInitialBoard()
                boardOverlay?.detectedBoard = internalBoard
                boardOverlay?.invalidate()
                statusText?.text = "🔄 Новая расстановка установлена!"
            }
        }

        val btnHide = makeBtn("🔽", Color.rgb(125, 80, 25), 0.5f).apply {
            setOnClickListener { setMinimizedState(true) }
        }

        val btnClose = makeBtn("✖", Color.rgb(155, 40, 40), 0.5f).apply {
            setOnClickListener { stopSelf() }
        }

        row.addView(btnScan)
        row.addView(btnMove)
        row.addView(btnAuto)
        row.addView(btnSide)
        row.addView(btnReset)
        row.addView(btnHide)
        row.addView(btnClose)
        rootPanel.addView(row)

        val pParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = (screenHeight * 0.055f).toInt()
        }
        panelParams = pParams

        statusText?.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0
            private var touchY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = pParams.y
                        touchY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        pParams.y = (startY + (e.rawY - touchY).toInt()).coerceIn(20, screenHeight - 200)
                        windowManager.updateViewLayout(rootPanel, pParams)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(rootPanel, pParams)

        val bubble = Button(this).apply {
            text = "♟️"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(230, 25, 135, 80))
            setPadding(10, 6, 10, 6)
            visibility = View.GONE
        }
        miniButton = bubble

        val bubParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = (screenHeight * 0.15f).toInt()
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var downX = 0f; private var downY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubParams.x; initialY = bubParams.y
                        downX = e.rawX; downY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        bubParams.x = (initialX - (e.rawX - downX).toInt()).coerceAtLeast(0)
                        bubParams.y = (initialY + (e.rawY - downY).toInt()).coerceIn(30, screenHeight - 150)
                        windowManager.updateViewLayout(bubble, bubParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(e.rawX - downX) < 20 && abs(e.rawY - downY) < 20) {
                            setMinimizedState(false)
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubble, bubParams)
    }

    private fun setEditMode(enabled: Boolean) {
        isEditCellsMode = enabled
        val view = boardOverlay ?: return
        val params = overlayParams ?: return
        view.touchInteractive = enabled
        if (enabled) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            statusText?.text = "✏️ Тап по клетке меняет фигуру. Жми [🔍 Скан]"
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { windowManager.updateViewLayout(view, params) } catch (_: Throwable) {}
    }

    private fun setMinimizedState(minimize: Boolean) {
        isMinimized = minimize
        if (minimize) {
            setEditMode(false)
            boardOverlay?.visibility = View.GONE
            controlPanel?.visibility = View.GONE
            miniButton?.visibility = View.VISIBLE
        } else {
            boardOverlay?.visibility = View.VISIBLE
            controlPanel?.visibility = View.VISIBLE
            miniButton?.visibility = View.GONE
        }
    }

    private fun forceScanFromScreenNow() {
        serviceScope.launch {
            if (isEditCellsMode) setEditMode(false)

            boardOverlay?.bestMoveUci = null
            boardOverlay?.ponderMoveUci = null
            boardOverlay?.invalidate()
            boardOverlay?.visibility = View.INVISIBLE
            statusText?.text = "📸 Жду окончания анимации..."
            // Ждём 140 мс, чтобы анимация съедания/взрыва ферзя в Duolingo полностью завершилась перед снимком
            delay(140L)

            val frame = latestFrameBitmap
            boardOverlay?.visibility = View.VISIBLE

            if (frame == null) {
                statusText?.text = "⚠️ Жду кадр... Нажми [🔍 Скан]"
                return@launch
            }

            val detectedY = withContext(Dispatchers.Default) {
                BoardRecognizer.findBoardTopY(frame, boardTopY, isChessComMode)
            }
            if (abs(detectedY - boardTopY) in 2..240) {
                boardTopY = detectedY
                overlayParams?.let { p ->
                    p.y = boardTopY
                    boardOverlay?.boardTopPx = boardTopY.toFloat()
                    try { windowManager.updateViewLayout(boardOverlay, p) } catch (_: Throwable) {}
                }
            }

            val scannedGrid = withContext(Dispatchers.Default) {
                BoardRecognizer.scanFullBoardFromScreen(frame, 0, boardTopY, screenWidth, isChessComMode, playerIsWhite)
            }

            if (scannedGrid != null) {
                var count = 0
                for (sr in 0..7) {
                    for (sc in 0..7) {
                        val br = if (playerIsWhite) sr else 7 - sr
                        val bc = if (playerIsWhite) sc else 7 - sc
                        val p = scannedGrid[sr][sc]
                        internalBoard[br][bc] = p
                        if (p != '.') count++
                    }
                }
                boardOverlay?.detectedBoard = internalBoard
                boardOverlay?.lastMovedCells = null
                boardOverlay?.invalidate()
                runEngineSuggestion(count)
            }
        }
    }

    private fun onCellEditTapped(r: Int, c: Int) {
        if (!isEditCellsMode) return
        val cycle = if (playerIsWhite) {
            charArrayOf('.', 'P', 'N', 'B', 'R', 'Q', 'K', 'p', 'n', 'b', 'r', 'q', 'k')
        } else {
            charArrayOf('.', 'p', 'n', 'b', 'r', 'q', 'k', 'P', 'N', 'B', 'R', 'Q', 'K')
        }
        val cur = internalBoard[r][c]
        val next = cycle[(cycle.indexOf(cur) + 1) % cycle.size]
        internalBoard[r][c] = next
        boardOverlay?.invalidate()
        val sq = "${('a' + c)}${8 - r}"
        statusText?.text = "✏️ $sq = $next (Жми [🔍 Скан] для хода)"
    }

    private fun executeMoveOnBothBoards(uci: String) {
        if (uci.length < 4 || isExecutingGesture) return
        val fromC = uci[0] - 'a'
        val fromR = 8 - (uci[1] - '0')
        val toC = uci[2] - 'a'
        val toR = 8 - (uci[3] - '0')

        applyMoveToInternalBoard(fromR, fromC, toR, toC)
        boardOverlay?.lastMovedCells = (fromR to fromC) to (toR to toC)
        boardOverlay?.bestMoveUci = null
        boardOverlay?.ponderMoveUci = null
        currentBestMoveUci = ""
        boardOverlay?.invalidate()

        val svc = AutoMoveService.instance
        if (svc == null) {
            statusText?.text = "⚠️ Включи Accessibility! Ход $uci отмечен."
            return
        }

        isExecutingGesture = true
        val overlay = boardOverlay ?: return
        statusText?.text = "🤖 Ход $uci..."

        svc.performChessMove(
            uciMove = uci,
            boardLeft = overlay.boardLeftPx,
            boardTop = boardTopY.toFloat(),
            boardSize = overlay.boardSizePx,
            whiteAtBottom = playerIsWhite
        ) {
            mainHandler.postDelayed({
                isExecutingGesture = false
                statusText?.text = "👀 Жду соперника..."
            }, 300L)
        }
    }

    private fun startScreenSyncLoop() {
        watcherJob?.cancel()
        watcherJob = serviceScope.launch {
            while (isActive) {
                delay(600L)
                if (isMinimized || isExecutingGesture || isEditCellsMode) continue
                val frame = latestFrameBitmap ?: continue

                val syncAction = withContext(Dispatchers.Default) {
                    analyzeScreenChanges(frame)
                }

                when (syncAction) {
                    is SyncResult.SingleMove -> {
                        val (from, to) = syncAction.from to syncAction.to
                        val movedPiece = internalBoard[from.first][from.second]
                        val wasEnemy = movedPiece != '.' && (movedPiece.isUpperCase() != playerIsWhite)
                        applyMoveToInternalBoard(from.first, from.second, to.first, to.second)
                        boardOverlay?.lastMovedCells = from to to
                        boardOverlay?.invalidate()

                        val uci = "${('a' + from.second)}${8 - from.first}${('a' + to.second)}${8 - to.first}"
                        if (wasEnemy) {
                            statusText?.text = "⚡ Соперник: $uci! Считаю..."
                            runEngineSuggestion()
                        } else {
                            statusText?.text = "✅ Ход $uci. Жду соперника..."
                        }
                    }
                    is SyncResult.NewBoardDetected -> {
                        forceScanFromScreenNow()
                    }
                    else -> {}
                }
            }
        }
    }

    private sealed class SyncResult {
        object NoChange : SyncResult()
        data class SingleMove(val from: Pair<Int, Int>, val to: Pair<Int, Int>) : SyncResult()
        object NewBoardDetected : SyncResult()
    }

    private fun analyzeScreenChanges(frame: Bitmap): SyncResult {
        val screenPresence = BoardRecognizer.scanPresence(frame, 0, boardTopY, screenWidth, isChessComMode)
            ?: return SyncResult.NoChange

        var vacatedPos: Pair<Int, Int>? = null
        var arrivedPos: Pair<Int, Int>? = null
        var vacatedCount = 0; var arrivedCount = 0; var totalDiffs = 0

        for (r in 0..7) {
            for (c in 0..7) {
                val sc = if (playerIsWhite) c else 7 - c
                val sr = if (playerIsWhite) r else 7 - r
                val vis = screenPresence[sr][sc]
                val mem = internalBoard[r][c]
                val memVis = when {
                    mem == '.' -> '.'
                    mem.isUpperCase() -> 'W'
                    else -> 'B'
                }

                if (vis != memVis) {
                    totalDiffs++
                    if (memVis != '.' && vis == '.') { vacatedCount++; vacatedPos = r to c }
                    else if (vis != '.') { arrivedCount++; arrivedPos = r to c }
                }
            }
        }

        if (totalDiffs == 0) return SyncResult.NoChange
        if (vacatedCount == 1 && arrivedCount == 1 && totalDiffs == 2 && vacatedPos != null && arrivedPos != null) {
            return SyncResult.SingleMove(vacatedPos, arrivedPos)
        }
        if (totalDiffs >= 4) return SyncResult.NewBoardDetected
        return SyncResult.NoChange
    }

    private fun runEngineSuggestion(scannedCount: Int = -1) {
        serviceScope.launch {
            val boardCopy = Array(8) { r -> internalBoard[r].clone() }
            val result = withContext(Dispatchers.Default) {
                chessEngine.analyze(boardCopy, whiteToMove = playerIsWhite, maxDepth = 4)
            }

            if (result.bestMove.length >= 4) {
                currentBestMoveUci = result.bestMove
                boardOverlay?.bestMoveUci = result.bestMove
                boardOverlay?.ponderMoveUci = result.ponderMove
                boardOverlay?.evalScoreCp = result.evalCp
                boardOverlay?.invalidate()

                val sign = if (result.evalCp >= 0) "+" else ""
                val info = if (scannedCount > 0) "[$scannedCount фиг.] " else ""
                statusText?.text = "💡 ${info}Ход: ${result.bestMove} ($sign${result.evalCp / 100f})"

                if (autoPlayEnabled) {
                    delay(250L)
                    executeMoveOnBothBoards(result.bestMove)
                }
            } else {
                statusText?.text = "⚠️ Ходов нет. Жми [🔍 Скан]"
            }
        }
    }

    private fun applyMoveToInternalBoard(fromR: Int, fromC: Int, toR: Int, toC: Int) {
        val piece = internalBoard[fromR][fromC]
        if (piece == '.') return
        internalBoard[fromR][fromC] = '.'

        val finalPiece = when {
            piece == 'P' && toR == 0 -> 'Q'
            piece == 'p' && toR == 7 -> 'q'
            else -> piece
        }
        internalBoard[toR][toC] = finalPiece

        if ((piece == 'K' || piece == 'k') && abs(toC - fromC) == 2) {
            if (toC == 6) {
                internalBoard[toR][5] = internalBoard[toR][7]
                internalBoard[toR][7] = '.'
            } else if (toC == 2) {
                internalBoard[toR][3] = internalBoard[toR][0]
                internalBoard[toR][0] = '.'
            }
        }
    }

    private fun createInitialBoard(): Array<CharArray> = arrayOf(
        charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
        charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
        charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
    )

    private fun setupVirtualDisplay() {
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenWidth
                val paddedWidth = screenWidth + rowPadding / pixelStride
                val bmp = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buffer)
                val finalBmp = if (paddedWidth != screenWidth) {
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                    bmp.recycle()
                    cropped
                } else bmp
                latestFrameBitmap = finalBmp
            } catch (_: Throwable) {
            } finally {
                image.close()
            }
        }, captureHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessBotScreen",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, captureHandler
        )
    }

    private fun stopCaptureResources() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        try { captureThread?.quitSafely() } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        watcherJob?.cancel()
        stopCaptureResources()
        try { boardOverlay?.let { windowManager.removeView(it) } } catch (_: Throwable) {}
        try { controlPanel?.let { windowManager.removeView(it) } } catch (_: Throwable) {}
        try { miniButton?.let { windowManager.removeView(it) } } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
