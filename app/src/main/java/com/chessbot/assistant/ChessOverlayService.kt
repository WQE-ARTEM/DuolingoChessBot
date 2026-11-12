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
    private var isDeleteMode = false

    // false = экран полностью свободен (клики сквозь доску в игру)
    // true = доска бота принимает тапы для выбора фигур
    private var boardTouchEnabled = false
    private var isMinimized = false

    private var gamePhase = 0
    private var selectedCell: Pair<Int, Int>? = null
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
                startOpponentWatcherLoop()
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
            .setContentText("Экран разблокирован + кнопка Скрыть")
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
        val boardTopY = (screenHeight * 0.352f).toInt()
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

        // ВАЖНО: По умолчанию FLAG_NOT_TOUCHABLE ВКЛЮЧЕН — ты можешь свободно пользоваться всем экраном!
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

        // Панель управления (компактная и перетаскиваемая за строку статуса)
        val rootPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 20, 28, 36))
            setPadding(10, 6, 10, 6)
        }
        controlPanel = rootPanel

        statusText = TextView(this).apply {
            text = "🔓 Экран свободен! Нажми [👆 Тап доски], чтобы указать ход"
            setTextColor(Color.rgb(120, 255, 160))
            textSize = 12f
            setPadding(6, 4, 6, 6)
        }
        rootPanel.addView(statusText)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun makeBtn(label: String, bg: Int = Color.rgb(45, 62, 78)): Button {
            return Button(this).apply {
                text = label
                textSize = 10.5f
                setTextColor(Color.WHITE)
                setBackgroundColor(bg)
                setPadding(6, 4, 6, 4)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(2, 2, 2, 2)
                layoutParams = lp
            }
        }

        val btnTouch = makeBtn("👆 Тап доски", Color.rgb(25, 115, 160)).apply {
            setOnClickListener {
                setBoardInteractive(!boardTouchEnabled)
            }
        }
        btnTouchMode = btnTouch

        val btnBest = makeBtn("▶️ Сходить", Color.rgb(35, 140, 75)).apply {
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
                if (autoPlayEnabled && gamePhase == 2 && currentBestMoveUci.length >= 4) {
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
                resetBoardState()
            }
        }

        val btnReset = makeBtn("🔄 Сброс").apply {
            setOnClickListener { resetBoardState() }
        }

        val btnEdit = makeBtn("🎮 Игра").apply {
            setOnClickListener {
                isFreeEditMode = !isFreeEditMode
                isDeleteMode = false
                selectedCell = null
                boardOverlay?.selectedBoardCell = null
                text = if (isFreeEditMode) "✏️ Ред." else "🎮 Игра"
                setBackgroundColor(if (isFreeEditMode) Color.rgb(160, 105, 20) else Color.rgb(45, 62, 78))
                if (isFreeEditMode) {
                    setBoardInteractive(true)
                    statusText?.text = "✏️ Режим ред.: переставляй фигуры на доске бота"
                } else {
                    setBoardInteractive(false)
                }
                boardOverlay?.invalidate()
            }
        }

        val btnDel = makeBtn("🗑").apply {
            layoutParams = LinearLayout.LayoutParams(85, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(2, 2, 2, 2)
            }
            setOnClickListener {
                isDeleteMode = !isDeleteMode
                if (isDeleteMode) setBoardInteractive(true)
                setBackgroundColor(if (isDeleteMode) Color.rgb(180, 45, 45) else Color.rgb(45, 62, 78))
                statusText?.text = if (isDeleteMode) "Тапни по фигуре на доске, чтобы удалить её" else "Удаление выкл."
            }
        }

        val btnClose = makeBtn("✖", Color.rgb(150, 40, 40)).apply {
            layoutParams = LinearLayout.LayoutParams(85, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(2, 2, 2, 2)
            }
            setOnClickListener { stopSelf() }
        }

        row1.addView(btnTouch)
        row1.addView(btnBest)
        row1.addView(btnAuto)
        row1.addView(btnHide)

        row2.addView(btnSide)
        row2.addView(btnReset)
        row2.addView(btnEdit)
        row2.addView(btnDel)
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
            y = (screenHeight * 0.08f).toInt()
        }
        panelParams = pParams

        // Позволяем перетаскивать панель вверх-вниз за текстовую строку статуса
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
                        pParams.y = (startY + (e.rawY - touchY).toInt()).coerceIn(40, screenHeight - 300)
                        windowManager.updateViewLayout(rootPanel, pParams)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(rootPanel, pParams)

        // Плавающая мини-кнопка "♟️", которая появляется, когда бот скрыт
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

    // Переключает режим: либо доска бота принимает тапы, либо весь экран полностью свободен для использования
    private fun setBoardInteractive(interactive: Boolean) {
        boardTouchEnabled = interactive
        val view = boardOverlay ?: return
        val params = overlayParams ?: return
        view.touchInteractive = interactive

        if (interactive) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            btnTouchMode?.text = "🔓 В игру"
            btnTouchMode?.setBackgroundColor(Color.rgb(210, 120, 20))
            statusText?.text = "👆 Тапни по доске бота: откуда ➔ куда"
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            btnTouchMode?.text = "👆 Тап доски"
            btnTouchMode?.setBackgroundColor(Color.rgb(25, 115, 160))
            statusText?.text = "🔓 Экран свободен! Можешь нажимать что угодно в телефоне"
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {}
    }

    // Полное скрытие / разворачивание оверлея и панели
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

    private fun resetBoardState() {
        internalBoard = createInitialBoard()
        selectedCell = null
        currentBestMoveUci = ""
        gamePhase = 0
        boardOverlay?.detectedBoard = internalBoard
        boardOverlay?.selectedBoardCell = null
        boardOverlay?.lastMovedCells = null
        boardOverlay?.bestMoveUci = null
        boardOverlay?.ponderMoveUci = null
        boardOverlay?.invalidate()

        setBoardInteractive(false)
        val sideStr = if (playerIsWhite) "Белые" else "Чёрные"
        statusText?.text = "🔄 Сброшено ($sideStr). Нажми [👆 Тап доски] для 1-го хода"
    }

    private fun onUserTappedBoard(r: Int, c: Int) {
        if (isExecutingGesture) return

        if (isDeleteMode) {
            internalBoard[r][c] = '.'
            isDeleteMode = false
            boardOverlay?.invalidate()
            setBoardInteractive(false)
            statusText?.text = "Фигура удалена. Экран снова свободен."
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

        if (!isFreeEditMode && targetPiece != '.' && targetPiece.isUpperCase() == movingPiece.isUpperCase()) {
            selectedCell = r to c
            boardOverlay?.selectedBoardCell = r to c
            boardOverlay?.invalidate()
            val sqName = "${('a' + c)}${8 - r}"
            statusText?.text = "Переключено на ${getPieceName(targetPiece)} ($sqName) ➔ куда?"
            return
        }

        selectedCell = null
        boardOverlay?.selectedBoardCell = null

        if (isFreeEditMode) {
            applyMoveToInternalBoard(fromR, fromC, r, c)
            boardOverlay?.invalidate()
            statusText?.text = "✏️ Переставлено на доске бота"
            return
        }

        // Как только ход указан — сразу возвращаем свободный экран!
        setBoardInteractive(false)

        val uci = toUci(fromR, fromC, r, c, movingPiece)
        val isPlayerPiece = movingPiece.isUpperCase() == playerIsWhite

        if (isPlayerPiece) {
            executeMoveOnBothBoards(uci)
        } else {
            applyMoveToInternalBoard(fromR, fromC, r, c)
            boardOverlay?.lastMovedCells = (fromR to fromC) to (r to c)
            boardOverlay?.invalidate()
            gamePhase = 2
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

        setBoardInteractive(false)

        val svc = AutoMoveService.instance
        if (svc == null) {
            gamePhase = 1
            statusText?.text = "⚠️ Спец. возможности выкл.! Ход $uci записан на доске."
            return
        }

        isExecutingGesture = true
        val overlay = boardOverlay ?: return

        statusText?.text = "🤖 Делаю ход $uci в игре..."

        svc.performChessMove(
            uciMove = uci,
            boardLeft = overlay.boardLeftPx,
            boardTop = overlay.boardTopPx,
            boardSize = overlay.boardSizePx,
            whiteAtBottom = playerIsWhite
        ) {
            mainHandler.postDelayed({
                isExecutingGesture = false
                gamePhase = 1
                statusText?.text = "👀 Жду ответ соперника (экран свободен)..."
            }, 250L)
        }
    }

    private fun startOpponentWatcherLoop() {
        watcherJob?.cancel()
        watcherJob = serviceScope.launch {
            while (isActive) {
                delay(450L)
                if (isMinimized || gamePhase != 1 || isExecutingGesture || isFreeEditMode) continue

                val frame = captureScreenBitmap() ?: continue
                val overlay = boardOverlay ?: continue
                val left = overlay.boardLeftPx.toInt()
                val top = overlay.boardTopPx.toInt()
                val size = overlay.boardSizePx.toInt()

                val detectedOpponentMove = withContext(Dispatchers.Default) {
                    detectOpponentMoveFromScreen(frame, left, top, size)
                }

                if (detectedOpponentMove != null) {
                    val (from, to) = detectedOpponentMove
                    applyMoveToInternalBoard(from.first, from.second, to.first, to.second)
                    boardOverlay?.lastMovedCells = from to to
                    boardOverlay?.invalidate()
                    gamePhase = 2
                    val uci = "${('a' + from.second)}${8 - from.first}${('a' + to.second)}${8 - to.first}"
                    statusText?.text = "⚡ Соперник сходил $uci! Считаю ответ..."
                    runEngineSuggestion()
                }
            }
        }
    }

    private fun detectOpponentMoveFromScreen(
        frame: Bitmap,
        left: Int,
        top: Int,
        size: Int
    ): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val screenPresence = BoardRecognizer.scanPresence(frame, left, top, size) ?: return null
        val enemyIsWhite = !playerIsWhite
        val enemyColorChar = if (enemyIsWhite) 'W' else 'B'

        val vacatedEnemySquares = mutableListOf<Pair<Int, Int>>()
        val arrivedEnemySquares = mutableListOf<Pair<Int, Int>>()

        for (r in 0..7) {
            for (c in 0..7) {
                val sc = if (playerIsWhite) c else 7 - c
                val sr = if (playerIsWhite) r else 7 - r
                val visColor = screenPresence[sr][sc]
                val memPiece = internalBoard[r][c]

                val hadEnemy = memPiece != '.' && (memPiece.isUpperCase() == enemyIsWhite)
                val nowHasEnemy = visColor == enemyColorChar

                if (hadEnemy && visColor == '.') {
                    vacatedEnemySquares.add(r to c)
                } else if (!hadEnemy && nowHasEnemy) {
                    arrivedEnemySquares.add(r to c)
                }
            }
        }

        if (vacatedEnemySquares.size == 1 && arrivedEnemySquares.size == 1) {
            return vacatedEnemySquares[0] to arrivedEnemySquares[0]
        }
        return null
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
                statusText?.text = "💡 Ход: ${result.bestMove} ($sign${result.evalCp / 100f}) ➔ жми [▶️ Сходить]"

                if (autoPlayEnabled) {
                    delay(300L)
                    executeMoveOnBothBoards(result.bestMove)
                }
            }
        }
    }

    private fun applyMoveToInternalBoard(fromR: Int, fromC: Int, toR: Int, toC: Int) {
        val piece = internalBoard[fromR][fromC]
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
        'k' -> "Король"; 'q' -> "Ферзь"; 'r' -> "Ладья"
        'b' -> "Слон"; 'n' -> "Конь"; 'p' -> "Пешка"
        else -> "Фигура"
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
