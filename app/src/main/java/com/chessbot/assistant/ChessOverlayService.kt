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
import kotlinx.coroutines.*
import kotlin.math.abs

class ChessOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var boardOverlay: BoardOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var controlPanel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var miniBubble: Button? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var statusText: TextView? = null
    private var btnTouchMode: Button? = null
    private var btnEditMode: Button? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var chessEngine: ChessEngine

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcherJob: Job? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private var playerIsWhite = true
    private var autoPlayEnabled = false
    private var isFreeEditMode = false

    private var boardTouchEnabled = false
    private var isMinimized = false
    private var boardTopY = 780

    private var selectedCell: Pair<Int, Int>? = null
    private var currentBestMoveUci: String = ""
    private var isExecutingGesture = false
    private var lastPositionSignature: String = ""

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
        // Выровнено под реальную позицию доски Duolingo (0.330f вместо старых 0.352f)
        boardTopY = (screenHeight * 0.330f).toInt()
        chessEngine = ChessEngine(this)
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
                startScreenSyncLoop()
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
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Duolingo Chess Bot активен")
            .setContentText("Авто-сканер доски + калибровка сетки")
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
            onBoardCellClick = { r, c -> onUserTappedBoard(r, c) }
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
            setBackgroundColor(Color.argb(235, 20, 28, 36))
            setPadding(8, 6, 8, 6)
        }
        controlPanel = rootPanel

        statusText = TextView(this).apply {
            text = "🔓 Экран свободен! Нажми [🔍 Скан] для любой задачи или партии"
            setTextColor(Color.rgb(120, 255, 160))
            textSize = 11.5f
            setPadding(6, 2, 6, 4)
        }
        rootPanel.addView(statusText)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun makeBtn(label: String, bg: Int = Color.rgb(45, 62, 78), weight: Float = 1f): Button {
            return Button(this).apply {
                text = label
                textSize = 10f
                setTextColor(Color.WHITE)
                setBackgroundColor(bg)
                setPadding(4, 4, 4, 4)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                lp.setMargins(2, 2, 2, 2)
                layoutParams = lp
            }
        }

        val btnScan = makeBtn("🔍 Скан", Color.rgb(20, 125, 150)).apply {
            setOnClickListener {
                forceScanFromScreenNow()
            }
        }

        val btnTouch = makeBtn("👆 Тап", Color.rgb(45, 95, 140)).apply {
            setOnClickListener {
                setBoardInteractive(!boardTouchEnabled)
            }
        }
        btnTouchMode = btnTouch

        val btnBest = makeBtn("▶️ Ход", Color.rgb(35, 140, 75)).apply {
            setOnClickListener {
                if (currentBestMoveUci.length >= 4) {
                    executeMoveOnBothBoards(currentBestMoveUci)
                } else {
                    runEngineSuggestion()
                }
            }
        }

        val btnAuto = makeBtn("Авто: ВЫКЛ").apply {
            setOnClickListener {
                autoPlayEnabled = !autoPlayEnabled
                text = if (autoPlayEnabled) "Авто: ВКЛ" else "Авто: ВЫКЛ"
                setBackgroundColor(if (autoPlayEnabled) Color.rgb(30, 130, 70) else Color.rgb(45, 62, 78))
                if (autoPlayEnabled && currentBestMoveUci.length >= 4) {
                    executeMoveOnBothBoards(currentBestMoveUci)
                }
            }
        }

        val btnHide = makeBtn("🔽 Скрыть", Color.rgb(120, 75, 25)).apply {
            setOnClickListener {
                setMinimizedState(true)
            }
        }

        val btnSide = makeBtn("⬜ Белые").apply {
            setOnClickListener {
                playerIsWhite = !playerIsWhite
                boardOverlay?.whiteAtBottom = playerIsWhite
                text = if (playerIsWhite) "⬜ Белые" else "⬛ Черные"
                forceScanFromScreenNow()
            }
        }

        val btnReset = makeBtn("🔄 Новая").apply {
            setOnClickListener { resetToNewStandardGame() }
        }

        val btnEdit = makeBtn("🎮 Игра").apply {
            setOnClickListener {
                isFreeEditMode = !isFreeEditMode
                selectedCell = null
                boardOverlay?.selectedBoardCell = null
                text = if (isFreeEditMode) "✏️ Ред." else "🎮 Игра"
                setBackgroundColor(if (isFreeEditMode) Color.rgb(160, 105, 20) else Color.rgb(45, 62, 78))
                if (isFreeEditMode) {
                    setBoardInteractive(true)
                    statusText?.text = "✏️ Ред.: тап по фигуре меняет её тип (P➔N➔B➔R➔Q➔K➔пусто)"
                } else {
                    setBoardInteractive(false)
                    runEngineSuggestion()
                }
                boardOverlay?.invalidate()
            }
        }
        btnEditMode = btnEdit

        // Кнопки точного сдвига доски вверх/вниз, если нужно подстроить сетку
        val btnUp = makeBtn("⬆️", Color.rgb(55, 75, 95), 0.55f).apply {
            setOnClickListener { shiftBoardY(-10) }
        }

        val btnDown = makeBtn("⬇️", Color.rgb(55, 75, 95), 0.55f).apply {
            setOnClickListener { shiftBoardY(10) }
        }

        val btnClose = makeBtn("✖", Color.rgb(150, 40, 40), 0.55f).apply {
            setOnClickListener { stopSelf() }
        }

        row1.addView(btnScan)
        row1.addView(btnTouch)
        row1.addView(btnBest)
        row1.addView(btnAuto)
        row1.addView(btnHide)

        row2.addView(btnSide)
        row2.addView(btnReset)
        row2.addView(btnEdit)
        row2.addView(btnUp)
        row2.addView(btnDown)
        row2.addView(btnClose)

        rootPanel.addView(row1)
        rootPanel.addView(row2)

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
                        pParams.y = (startY + (e.rawY - touchY).toInt()).coerceIn(30, screenHeight - 300)
                        windowManager.updateViewLayout(rootPanel, pParams)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(rootPanel, pParams)

        val bubble = Button(this).apply {
            text = "♟️ Бот"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(230, 25, 135, 80))
            setPadding(16, 10, 16, 10)
            visibility = View.GONE
        }
        miniBubble = bubble

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
        bubbleParams = bubParams

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var downX = 0f
            private var downY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubParams.x
                        initialY = bubParams.y
                        downX = e.rawX
                        downY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        bubParams.x = (initialX - (e.rawX - downX).toInt()).coerceAtLeast(0)
                        bubParams.y = (initialY + (e.rawY - downY).toInt()).coerceIn(40, screenHeight - 200)
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

    private fun shiftBoardY(deltaPx: Int) {
        boardTopY = (boardTopY + deltaPx).coerceIn(150, screenHeight - screenWidth - 50)
        val view = boardOverlay ?: return
        val params = overlayParams ?: return
        params.y = boardTopY
        view.boardTopPx = boardTopY.toFloat()
        try {
            windowManager.updateViewLayout(view, params)
            view.invalidate()
        } catch (_: Throwable) {}
        statusText?.text = "📐 Сетка доски сдвинута (Y=$boardTopY). Жми [🔍 Скан]"
    }

    private fun setBoardInteractive(interactive: Boolean) {
        boardTouchEnabled = interactive
        val view = boardOverlay ?: return
        val params = overlayParams ?: return
        view.touchInteractive = interactive

        if (interactive) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            btnTouchMode?.text = "🔓 В игру"
            btnTouchMode?.setBackgroundColor(Color.rgb(210, 120, 20))
            if (!isFreeEditMode) {
                statusText?.text = "👆 Тапни по доске бота: откуда ➔ куда"
            }
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            btnTouchMode?.text = "👆 Тап"
            btnTouchMode?.setBackgroundColor(Color.rgb(45, 95, 140))
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {}
    }

    private fun setMinimizedState(minimize: Boolean) {
        isMinimized = minimize
        if (minimize) {
            setBoardInteractive(false)
            boardOverlay?.visibility = View.GONE
            controlPanel?.visibility = View.GONE
            miniBubble?.visibility = View.VISIBLE
        } else {
            boardOverlay?.visibility = View.VISIBLE
            controlPanel?.visibility = View.VISIBLE
            miniBubble?.visibility = View.GONE
        }
    }

    private fun resetToNewStandardGame() {
        isFreeEditMode = false
        btnEditMode?.text = "🎮 Игра"
        btnEditMode?.setBackgroundColor(Color.rgb(45, 62, 78))
        internalBoard = createInitialBoard()
        selectedCell = null
        currentBestMoveUci = ""
        lastPositionSignature = ""
        boardOverlay?.detectedBoard = internalBoard
        boardOverlay?.selectedBoardCell = null
        boardOverlay?.lastMovedCells = null
        boardOverlay?.bestMoveUci = null
        boardOverlay?.ponderMoveUci = null
        boardOverlay?.invalidate()
        setBoardInteractive(false)
        statusText?.text = "🔄 Стандартная начальная доска установлена!"
    }

    // Принудительное полное сканирование доски с экрана (и авто-калибровка высоты доски)
    private fun forceScanFromScreenNow() {
        serviceScope.launch {
            isFreeEditMode = false
            btnEditMode?.text = "🎮 Игра"
            btnEditMode?.setBackgroundColor(Color.rgb(45, 62, 78))
            setBoardInteractive(false)

            // Временно прячем оверлей на 80 мс, чтобы сфотографировать чистый экран игры без наших значков
            boardOverlay?.visibility = View.INVISIBLE
            delay(90L)
            val frame = captureScreenBitmap()
            boardOverlay?.visibility = View.VISIBLE

            if (frame == null) {
                statusText?.text = "⚠️ Кадр экрана ещё не готов, нажми [🔍 Скан] ещё раз"
                return@launch
            }

            withContext(Dispatchers.Default) {
                val detectedY = BoardRecognizer.findBoardTopY(frame, boardTopY)
                if (abs(detectedY - boardTopY) in 4..180) {
                    boardTopY = detectedY
                }
            }

            // Выравниваем окно оверлея ровно по найденному краю доски
            overlayParams?.let { p ->
                p.y = boardTopY
                boardOverlay?.boardTopPx = boardTopY.toFloat()
                try { windowManager.updateViewLayout(boardOverlay, p) } catch (_: Throwable) {}
            }

            val scannedScreenGrid = withContext(Dispatchers.Default) {
                BoardRecognizer.scanFullBoardFromScreen(frame, 0, boardTopY, screenWidth)
            }

            if (scannedScreenGrid != null) {
                for (sr in 0..7) {
                    for (sc in 0..7) {
                        val br = if (playerIsWhite) sr else 7 - sr
                        val bc = if (playerIsWhite) sc else 7 - sc
                        internalBoard[br][bc] = scannedScreenGrid[sr][sc]
                    }
                }
                boardOverlay?.detectedBoard = internalBoard
                boardOverlay?.lastMovedCells = null
                boardOverlay?.invalidate()
                lastPositionSignature = buildPresenceSignature(internalBoard)
                runEngineSuggestion()
            }
        }
    }

    private fun onUserTappedBoard(r: Int, c: Int) {
        if (isExecutingGesture) return

        // В режиме "✏️ Ред." тап по клетке циклически меняет фигуру (удобно для любых головоломок!)
        if (isFreeEditMode) {
            val cycle = if (playerIsWhite) {
                charArrayOf('.', 'P', 'N', 'B', 'R', 'Q', 'K', 'p', 'n', 'b', 'r', 'q', 'k')
            } else {
                charArrayOf('.', 'p', 'n', 'b', 'r', 'q', 'k', 'P', 'N', 'B', 'R', 'Q', 'K')
            }
            val cur = internalBoard[r][c]
            val idx = cycle.indexOf(cur)
            val next = cycle[(idx + 1) % cycle.size]
            internalBoard[r][c] = next
            boardOverlay?.invalidate()
            val sqName = "${('a' + c)}${8 - r}"
            statusText?.text = "✏️ Клетка $sqName: ${if (next == '.') "Пусто" else getPieceName(next)}"
            return
        }

        val currentSel = selectedCell
        if (currentSel == null) {
            val piece = internalBoard[r][c]
            if (piece == '.') return
            selectedCell = r to c
            boardOverlay?.selectedBoardCell = r to c
            boardOverlay?.invalidate()
            val sqName = "${('a' + c)}${8 - r}"
            statusText?.text = "Выбрана ${getPieceName(piece)} ($sqName) ➔ тапни куда пойти"
            return
        }

        val (fromR, fromC) = currentSel
        if (fromR == r && fromC == c) {
            selectedCell = null
            boardOverlay?.selectedBoardCell = null
            boardOverlay?.invalidate()
            statusText?.text = "Выбор отменён"
            return
        }

        val movingPiece = internalBoard[fromR][fromC]
        val targetPiece = internalBoard[r][c]

        if (targetPiece != '.' && targetPiece.isUpperCase() == movingPiece.isUpperCase()) {
            selectedCell = r to c
            boardOverlay?.selectedBoardCell = r to c
            boardOverlay?.invalidate()
            val sqName = "${('a' + c)}${8 - r}"
            statusText?.text = "Переключено на ${getPieceName(targetPiece)} ($sqName) ➔ куда?"
            return
        }

        selectedCell = null
        boardOverlay?.selectedBoardCell = null
        setBoardInteractive(false)

        val uci = toUci(fromR, fromC, r, c, movingPiece)
        val isPlayerPiece = movingPiece.isUpperCase() == playerIsWhite

        if (isPlayerPiece) {
            executeMoveOnBothBoards(uci)
        } else {
            applyMoveToInternalBoard(fromR, fromC, r, c)
            boardOverlay?.lastMovedCells = (fromR to fromC) to (r to c)
            boardOverlay?.invalidate()
            lastPositionSignature = buildPresenceSignature(internalBoard)
            runEngineSuggestion()
        }
    }

    private fun executeMoveOnBothBoards(uci: String) {
        if (uci.length < 4) return
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
        lastPositionSignature = buildPresenceSignature(internalBoard)

        setBoardInteractive(false)

        val svc = AutoMoveService.instance
        if (svc == null) {
            statusText?.text = "⚠️ Включи Спец. возможности! Ход $uci записан."
            return
        }

        isExecutingGesture = true
        val overlay = boardOverlay ?: return

        statusText?.text = "🤖 Делаю ход $uci в игре..."

        svc.performChessMove(
            uciMove = uci,
            boardLeft = overlay.boardLeftPx,
            boardTop = boardTopY.toFloat(),
            boardSize = overlay.boardSizePx,
            whiteAtBottom = playerIsWhite
        ) {
            mainHandler.postDelayed({
                isExecutingGesture = false
                statusText?.text = "👀 Жду ответ соперника (или новую задачу)..."
            }, 350L)
        }
    }

    // Непрерывный умный цикл: сам ловит и одиночный ход соперника, и начало новой задачи/партии!
    private fun startScreenSyncLoop() {
        watcherJob?.cancel()
        watcherJob = serviceScope.launch {
            while (isActive) {
                delay(550L)
                if (isMinimized || isExecutingGesture || isFreeEditMode || boardTouchEnabled) continue

                val frame = captureScreenBitmap() ?: continue

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
                        lastPositionSignature = buildPresenceSignature(internalBoard)

                        val uci = "${('a' + from.second)}${8 - from.first}${('a' + to.second)}${8 - to.first}"
                        if (wasEnemy) {
                            statusText?.text = "⚡ Соперник сходил $uci! Считаю ответ..."
                            runEngineSuggestion()
                        } else {
                            statusText?.text = "✅ Твой ход $uci зафиксирован. Жду соперника..."
                        }
                    }
                    is SyncResult.NewBoardDetected -> {
                        // Началась новая партия или головоломка — полностью обновляем доску с экрана!
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
        val screenPresence = BoardRecognizer.scanPresence(frame, 0, boardTopY, screenWidth) ?: return SyncResult.NoChange

        val vacated = mutableListOf<Pair<Int, Int>>()
        val arrived = mutableListOf<Pair<Int, Int>>()
        var totalDiffs = 0

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
                    if (memVis != '.' && vis == '.') {
                        vacated.add(r to c)
                    } else if (vis != '.') {
                        arrived.add(r to c)
                    }
                }
            }
        }

        if (totalDiffs == 0) return SyncResult.NoChange

        // Если изменились ровно 2 клетки (1 освободилась и 1 занялась/срублена) — это обычный шахматный ход
        if (vacated.size == 1 && arrived.size == 1 && totalDiffs == 2) {
            return SyncResult.SingleMove(vacated[0], arrived[0])
        }

        // Если на экране изменилось 4 и более клеток (началась новая задача или новая партия!)
        if (totalDiffs >= 4) {
            return SyncResult.NewBoardDetected
        }

        return SyncResult.NoChange
    }

    private fun buildPresenceSignature(board: Array<CharArray>): String {
        val sb = StringBuilder(64)
        for (r in 0..7) {
            for (c in 0..7) {
                sb.append(board[r][c])
            }
        }
        return sb.toString()
    }

    private fun runEngineSuggestion() {
        serviceScope.launch {
            val boardCopy = Array(8) { r -> internalBoard[r].clone() }
            val result = withContext(Dispatchers.Default) {
                chessEngine.analyze(boardCopy, whiteToMove = playerIsWhite, maxDepth = 5)
            }

            if (result.bestMove.length >= 4) {
                currentBestMoveUci = result.bestMove
                boardOverlay?.bestMoveUci = result.bestMove
                boardOverlay?.ponderMoveUci = result.ponderMove
                boardOverlay?.evalScoreCp = result.evalCp
                boardOverlay?.invalidate()

                val sign = if (result.evalCp >= 0) "+" else ""
                statusText?.text = "💡 Лучший ход: ${result.bestMove} ($sign${result.evalCp / 100f}) ➔ жми [▶️ Ход]"

                if (autoPlayEnabled) {
                    delay(300L)
                    executeMoveOnBothBoards(result.bestMove)
                }
            } else {
                statusText?.text = "⚠️ Ходов не найдено. Нажми [🔍 Скан]"
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

    private fun toUci(fromR: Int, fromC: Int, toR: Int, toC: Int, piece: Char): String {
        val f1 = ('a' + fromC)
        val r1 = ('1' + (7 - fromR))
        val f2 = ('a' + toC)
        val r2 = ('1' + (7 - toR))
        val isPromo = (piece == 'P' && toR == 0) || (piece == 'p' && toR == 7)
        return if (isPromo) "$f1$r1$f2${r2}q" else "$f1$r1$f2$r2"
    }

    private fun getPieceName(p: Char): String = when (p.lowercaseChar()) {
        'k' -> "Король ($p)"; 'q' -> "Ферзь ($p)"; 'r' -> "Ладья ($p)"
        'b' -> "Слон ($p)"; 'n' -> "Конь ($p)"; 'p' -> "Пешка ($p)"
        else -> "Пусто"
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

    private fun captureScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try { reader.acquireLatestImage() } catch (_: Throwable) { null } ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val paddedWidth = screenWidth + rowPadding / pixelStride
            val bmp = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            if (paddedWidth != screenWidth) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                bmp.recycle()
                cropped
            } else bmp
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        }
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
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { mediaProjection?.stop() } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        watcherJob?.cancel()
        stopCaptureResources()
        try { boardOverlay?.let { windowManager.removeView(it) } } catch (_: Throwable) {}
        try { controlPanel?.let { windowManager.removeView(it) } } catch (_: Throwable) {}
        try { miniBubble?.let { windowManager.removeView(it) } } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
