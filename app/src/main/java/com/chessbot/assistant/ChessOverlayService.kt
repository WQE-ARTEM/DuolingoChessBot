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
    private var btnSideView: Button? = null
    private var btnAutoView: Button? = null

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
    private var autoPlayEnabled = true
    private var isMinimized = false
    private var boardTopY = 830

    private var currentBestMoveUci: String = ""
    private var isExecutingGesture = false
    private var waitingForOpponent = false
    private var lastAutoOpeningHash: String = ""

    // Если бот нажал на экран во время анимации Оскара и ход не прошёл, храним его для авто-повтора
    private var pendingBotMove: ChessEngine.Move? = null
    private var pendingMoveRetries = 0

    private var internalBoard = BoardRecognizer.createInitialBoard()

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
        // В Duolingo с заголовком "СЫГРАЕМ В ШАХМАТЫ" доска начинается ровно на 0.351f высоты экрана
        boardTopY = (screenHeight * 0.351f).toInt()
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
                (screenHeight * 0.351f).toInt()
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
                startAutonomousGameLoop()
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
            .setContentTitle("Chess Bot ($modeName) — Автопилот")
            .setContentText("Стабильная сетка + авто-повтор хода")
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
            text = "$modeTitle | 🤖 Автопилот ВКЛ — готов к игре!"
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

        val btnScan = makeBtn("🔍 Скан", Color.rgb(20, 125, 155), 1.0f).apply {
            setOnClickListener { forceFullRescan() }
        }

        val btnMove = makeBtn("▶️ Ход", Color.rgb(35, 145, 75), 1.0f).apply {
            setOnClickListener {
                if (currentBestMoveUci.length >= 4) {
                    val m = parseUciMove(currentBestMoveUci)
                    if (m != null) executeBotMove(m)
                } else {
                    runEngineAndMaybePlay()
                }
            }
        }

        val btnAuto = makeBtn("🤖 ВКЛ", Color.rgb(30, 135, 70), 1.0f).apply {
            setOnClickListener {
                autoPlayEnabled = !autoPlayEnabled
                text = if (autoPlayEnabled) "🤖 ВКЛ" else "🤖 ВЫКЛ"
                setBackgroundColor(if (autoPlayEnabled) Color.rgb(30, 135, 70) else Color.rgb(45, 62, 78))
                if (autoPlayEnabled && !waitingForOpponent) {
                    runEngineAndMaybePlay()
                }
            }
        }
        btnAutoView = btnAuto

        val btnSide = makeBtn("⬜ Бел", Color.rgb(55, 72, 90), 0.85f).apply {
            setOnClickListener {
                playerIsWhite = !playerIsWhite
                boardOverlay?.whiteAtBottom = playerIsWhite
                text = if (playerIsWhite) "⬜ Бел" else "⬛ Чер"
                lastAutoOpeningHash = ""
                pendingBotMove = null
                forceFullRescan()
            }
        }
        btnSideView = btnSide

        val btnReset = makeBtn("🔄 Новая", Color.rgb(105, 65, 145), 0.9f).apply {
            setOnClickListener {
                lastAutoOpeningHash = ""
                pendingBotMove = null
                internalBoard = BoardRecognizer.createInitialBoard()
                waitingForOpponent = false
                boardOverlay?.detectedBoard = internalBoard
                boardOverlay?.bestMoveUci = null
                boardOverlay?.invalidate()
                forceFullRescan()
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
            y = (screenHeight * 0.05f).toInt()
        }
        panelParams = pParams

        statusText?.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0
            private var touchY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = pParams.y; touchY = e.rawY
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

    private fun setMinimizedState(minimize: Boolean) {
        isMinimized = minimize
        if (minimize) {
            boardOverlay?.visibility = View.GONE
            controlPanel?.visibility = View.GONE
            miniButton?.visibility = View.VISIBLE
        } else {
            boardOverlay?.visibility = View.VISIBLE
            controlPanel?.visibility = View.VISIBLE
            miniButton?.visibility = View.GONE
        }
    }

    private fun startAutonomousGameLoop() {
        watcherJob?.cancel()
        watcherJob = serviceScope.launch {
            var prevSignature = ""
            var stableFrames = 0
            var initialCalibrated = false

            while (isActive) {
                delay(260L)
                if (isMinimized || isExecutingGesture) {
                    stableFrames = 0
                    continue
                }

                val frame = latestFrameBitmap ?: continue

                // Калибруем Y только при старте (в строгом коридоре 0.310..0.372), чтобы сетка никогда не прыгала на Оскара
                if (!initialCalibrated) {
                    val newTopY = withContext(Dispatchers.Default) {
                        BoardRecognizer.findBoardTopY(frame, boardTopY, isChessComMode)
                    }
                    if (abs(newTopY - boardTopY) in 2..120) {
                        boardTopY = newTopY
                        overlayParams?.let { p ->
                            p.y = boardTopY
                            boardOverlay?.boardTopPx = boardTopY.toFloat()
                            try { windowManager.updateViewLayout(boardOverlay, p) } catch (_: Throwable) {}
                        }
                    }
                    initialCalibrated = true
                }

                val screenPres = withContext(Dispatchers.Default) {
                    BoardRecognizer.scanScreenPresence(frame, boardTopY, screenWidth, isChessComMode)
                } ?: continue

                val sig = presenceToString(screenPres)
                if (sig != prevSignature) {
                    prevSignature = sig
                    stableFrames = 1
                    continue
                }
                stableFrames++
                if (stableFrames < 2) continue

                val detectedSide = BoardRecognizer.detectPlayerColorFromPresence(screenPres, playerIsWhite)
                if (detectedSide != playerIsWhite) {
                    playerIsWhite = detectedSide
                    boardOverlay?.whiteAtBottom = playerIsWhite
                    btnSideView?.text = if (playerIsWhite) "⬜ Бел" else "⬛ Чер"
                }

                val bp = BoardRecognizer.toBoardPresence(screenPres, playerIsWhite)

                // 1. Проверяем, прошёл ли наш последний авто-ход на реальном экране игры!
                val pending = pendingBotMove
                if (pending != null) {
                    val myColorChar = if (playerIsWhite) 'W' else 'B'
                    // Если наша фигура всё ещё стоит на стартовой клетке (fromR, fromC) — значит игра пропустила клик из-за анимации Оскара!
                    if (bp[pending.fromR][pending.fromC] == myColorChar && bp[pending.toR][pending.toC] != myColorChar) {
                        if (pendingMoveRetries < 3 && autoPlayEnabled) {
                            pendingMoveRetries++
                            statusText?.text = "🔄 Повтор хода ${pending.toUci()} после анимации..."
                            dispatchMoveGestureOnly(pending)
                            continue
                        } else {
                            pendingBotMove = null
                        }
                    } else {
                        // Ход успешно выполнился на экране! Фиксируем его в internalBoard
                        applyMoveToBoard(internalBoard, pending)
                        boardOverlay?.invalidate()
                        pendingBotMove = null
                        pendingMoveRetries = 0
                    }
                }

                // 2. Проверяем старт новой партии (0 ходов или 1 ход Белых)
                val openingBoard = BoardRecognizer.tryDetectOpeningBoard(bp)
                if (openingBoard != null) {
                    val openHash = presenceToString(bp) + "_$playerIsWhite"
                    if (openHash != lastAutoOpeningHash) {
                        lastAutoOpeningHash = openHash
                        internalBoard = openingBoard
                        pendingBotMove = null
                        boardOverlay?.detectedBoard = internalBoard
                        boardOverlay?.bestMoveUci = null
                        boardOverlay?.invalidate()

                        val midPieces = (2..5).sumOf { r -> (0..7).count { c -> bp[r][c] != '.' } }
                        val isMyTurnAtStart = (playerIsWhite && midPieces == 0) || (!playerIsWhite && midPieces == 1)
                        if (isMyTurnAtStart) {
                            waitingForOpponent = false
                            statusText?.text = "🚀 Новая партия! Делаю 1-й ход..."
                            runEngineAndMaybePlay()
                        } else {
                            waitingForOpponent = true
                            statusText?.text = "👀 Новая партия. Жду 1-й ход соперника..."
                        }
                        continue
                    }
                }

                // 3. Сопоставляем экран с internalBoard через легальные ходы
                val diffCount = countPresenceDiffs(internalBoard, bp)
                if (diffCount == 0) continue

                val opponentMove = findMatchingLegalMove(internalBoard, !playerIsWhite, bp)
                if (opponentMove != null) {
                    applyMoveToBoard(internalBoard, opponentMove)
                    boardOverlay?.invalidate()
                    waitingForOpponent = false
                    statusText?.text = "⚡ Соперник: ${opponentMove.toUci()}! Отвечаю..."
                    runEngineAndMaybePlay()
                    continue
                }

                val playerManualMove = findMatchingLegalMove(internalBoard, playerIsWhite, bp)
                if (playerManualMove != null) {
                    applyMoveToBoard(internalBoard, playerManualMove)
                    boardOverlay?.bestMoveUci = null
                    boardOverlay?.invalidate()
                    waitingForOpponent = true
                    statusText?.text = "✅ Ход ${playerManualMove.toUci()} учтён. Жду соперника..."
                    continue
                }

                val twoPly = findMatchingTwoPlyMoves(internalBoard, playerIsWhite, bp)
                if (twoPly != null) {
                    applyMoveToBoard(internalBoard, twoPly.first)
                    applyMoveToBoard(internalBoard, twoPly.second)
                    boardOverlay?.invalidate()
                    waitingForOpponent = false
                    statusText?.text = "⚡ Ответ соперника ${twoPly.second.toUci()}! Хожу..."
                    runEngineAndMaybePlay()
                }
            }
        }
    }

    private fun forceFullRescan() {
        serviceScope.launch {
            pendingBotMove = null
            boardOverlay?.bestMoveUci = null
            boardOverlay?.visibility = View.INVISIBLE
            delay(130L)
            val cleanFrame = latestFrameBitmap
            boardOverlay?.visibility = View.VISIBLE

            if (cleanFrame == null) {
                statusText?.text = "⚠️ Жду кадр экрана..."
                return@launch
            }

            val detY = withContext(Dispatchers.Default) {
                BoardRecognizer.findBoardTopY(cleanFrame, boardTopY, isChessComMode)
            }
            boardTopY = detY
            overlayParams?.let { p ->
                p.y = boardTopY
                boardOverlay?.boardTopPx = boardTopY.toFloat()
                try { windowManager.updateViewLayout(boardOverlay, p) } catch (_: Throwable) {}
            }

            val screenPres = withContext(Dispatchers.Default) {
                BoardRecognizer.scanScreenPresence(cleanFrame, boardTopY, screenWidth, isChessComMode)
            }
            if (screenPres != null) {
                playerIsWhite = BoardRecognizer.detectPlayerColorFromPresence(screenPres, playerIsWhite)
                boardOverlay?.whiteAtBottom = playerIsWhite
                btnSideView?.text = if (playerIsWhite) "⬜ Бел" else "⬛ Чер"
            }

            val rebuilt = withContext(Dispatchers.Default) {
                BoardRecognizer.reconstructBoardFromPresence(
                    cleanFrame, boardTopY, screenWidth,
                    isChessComMode, playerIsWhite, internalBoard
                )
            }
            internalBoard = rebuilt
            boardOverlay?.detectedBoard = internalBoard
            boardOverlay?.invalidate()
            waitingForOpponent = false
            runEngineAndMaybePlay()
        }
    }

    private fun findMatchingLegalMove(
        board: Array<CharArray>,
        sideIsWhite: Boolean,
        targetPresence: Array<CharArray>
    ): ChessEngine.Move? {
        val legal = chessEngine.generateLegalMoves(board, sideIsWhite)
        for (m in legal) {
            val copy = Array(8) { r -> board[r].clone() }
            applyMoveToBoard(copy, m)
            if (countPresenceDiffs(copy, targetPresence) == 0) {
                return m
            }
        }
        return null
    }

    private fun findMatchingTwoPlyMoves(
        board: Array<CharArray>,
        firstSideWhite: Boolean,
        targetPresence: Array<CharArray>
    ): Pair<ChessEngine.Move, ChessEngine.Move>? {
        val firstMoves = chessEngine.generateLegalMoves(board, firstSideWhite)
        for (m1 in firstMoves) {
            val copy1 = Array(8) { r -> board[r].clone() }
            applyMoveToBoard(copy1, m1)
            val secondMoves = chessEngine.generateLegalMoves(copy1, !firstSideWhite)
            for (m2 in secondMoves) {
                val copy2 = Array(8) { r -> copy1[r].clone() }
                applyMoveToBoard(copy2, m2)
                if (countPresenceDiffs(copy2, targetPresence) == 0) {
                    return m1 to m2
                }
            }
        }
        return null
    }

    private fun countPresenceDiffs(board: Array<CharArray>, targetPresence: Array<CharArray>): Int {
        var diffs = 0
        for (r in 0..7) {
            for (c in 0..7) {
                val p = board[r][c]
                val bVis = when {
                    p == '.' -> '.'
                    p.isUpperCase() -> 'W'
                    else -> 'B'
                }
                if (bVis != targetPresence[r][c]) diffs++
            }
        }
        return diffs
    }

    private fun presenceToString(pres: Array<CharArray>): String {
        val sb = StringBuilder(64)
        for (r in 0..7) for (c in 0..7) sb.append(pres[r][c])
        return sb.toString()
    }

    private fun applyMoveToBoard(board: Array<CharArray>, m: ChessEngine.Move) {
        val piece = board[m.fromR][m.fromC]
        if (piece == '.') return
        board[m.fromR][m.fromC] = '.'

        if ((piece == 'P' || piece == 'p') && m.fromC != m.toC && board[m.toR][m.toC] == '.') {
            board[m.fromR][m.toC] = '.'
        }

        val finalPiece = when {
            m.promo != '.' -> if (piece.isUpperCase()) m.promo.uppercaseChar() else m.promo.lowercaseChar()
            piece == 'P' && m.toR == 0 -> 'Q'
            piece == 'p' && m.toR == 7 -> 'q'
            else -> piece
        }
        board[m.toR][m.toC] = finalPiece

        if ((piece == 'K' || piece == 'k') && abs(m.toC - m.fromC) == 2) {
            if (m.toC == 6) {
                board[m.toR][5] = board[m.toR][7]
                board[m.toR][7] = '.'
            } else if (m.toC == 2) {
                board[m.toR][3] = board[m.toR][0]
                board[m.toR][0] = '.'
            }
        }
    }

    private fun parseUciMove(uci: String): ChessEngine.Move? {
        if (uci.length < 4) return null
        val fromC = uci[0] - 'a'
        val fromR = 8 - (uci[1] - '0')
        val toC = uci[2] - 'a'
        val toR = 8 - (uci[3] - '0')
        val promo = if (uci.length >= 5) uci[4] else '.'
        return ChessEngine.Move(fromR, fromC, toR, toC, promo)
    }

    private fun runEngineAndMaybePlay() {
        serviceScope.launch {
            val boardCopy = Array(8) { r -> internalBoard[r].clone() }
            val result = withContext(Dispatchers.Default) {
                chessEngine.analyze(boardCopy, whiteToMove = playerIsWhite, maxDepth = 4)
            }

            if (result.bestMove.length >= 4) {
                currentBestMoveUci = result.bestMove
                boardOverlay?.bestMoveUci = result.bestMove
                boardOverlay?.evalScoreCp = result.evalCp
                boardOverlay?.invalidate()

                val sign = if (result.evalCp >= 0) "+" else ""
                statusText?.text = "💡 Ход: ${result.bestMove} ($sign${result.evalCp / 100f})"

                if (autoPlayEnabled) {
                    delay(240L)
                    val moveObj = parseUciMove(result.bestMove)
                    if (moveObj != null) executeBotMove(moveObj)
                }
            } else {
                statusText?.text = "🏁 Партия завершена (или жми [🔄 Новая])"
            }
        }
    }

    private fun executeBotMove(moveObj: ChessEngine.Move) {
        if (isExecutingGesture) return
        val uci = moveObj.toUci()
        boardOverlay?.bestMoveUci = null
        currentBestMoveUci = ""
        boardOverlay?.invalidate()

        val svc = AutoMoveService.instance
        if (svc == null) {
            applyMoveToBoard(internalBoard, moveObj)
            boardOverlay?.invalidate()
            waitingForOpponent = true
            statusText?.text = "⚠️ Включи Accessibility! Сделай ход $uci"
            return
        }

        // Сохраняем ход в pendingBotMove: он запишется в internalBoard после проверки, что фигура реально сдвинулась на экране!
        pendingBotMove = moveObj
        pendingMoveRetries = 0
        dispatchMoveGestureOnly(moveObj)
    }

    private fun dispatchMoveGestureOnly(moveObj: ChessEngine.Move) {
        val svc = AutoMoveService.instance ?: return
        val overlay = boardOverlay ?: return
        val uci = moveObj.toUci()

        isExecutingGesture = true
        waitingForOpponent = true
        statusText?.text = "🤖 Играю $uci..."

        svc.performChessMove(
            uciMove = uci,
            boardLeft = overlay.boardLeftPx,
            boardTop = boardTopY.toFloat(),
            boardSize = overlay.boardSizePx,
            whiteAtBottom = playerIsWhite
        ) {
            mainHandler.postDelayed({
                isExecutingGesture = false
                statusText?.text = "👀 Жду ответ соперника..."
            }, 520L)
        }
    }

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
