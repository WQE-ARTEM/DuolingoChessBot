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
    private var overlayParams: WindowManager.LayoutParams? = null
    private var controlPanel: LinearLayout? = null
    private var statusText: TextView? = null
    private var btnPlayBest: Button? = null
    private var btnPassThrough: Button? = null

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

    // Настройки партии
    private var playerIsWhite = true
    private var autoPlayEnabled = false
    private var isFreeEditMode = false
    private var isDeleteMode = false
    private var passThroughTouch = false

    // Фазы:
    // 0 = Ждём 1-й ход (ты ходишь сам на доске бота, он дублирует в игру)
    // 1 = Ждём ход соперника (Оскара) — определяется автоматически по экрану или твоим тапом
    // 2 = Очередь бота (предлагает лучший ход стрелкой / кнопкой "▶️ Сходить" или ходит сам при Авто: ВКЛ)
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
            .setContentText("Интерактивная доска бота + дублирование ходов")
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
            boardTopPx = screenHeight * 0.352f
            boardSizePx = screenWidth.toFloat()
            whiteAtBottom = playerIsWhite
            detectedBoard = internalBoard
            touchInteractive = true
            onBoardCellClick = { r, c -> onUserTappedBoard(r, c) }
        }
        boardOverlay = overlayView

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        overlayParams = params
        windowManager.addView(overlayView, params)

        // Компактная двухрядная панель управления
        val rootPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 22, 30, 38))
            setPadding(12, 8, 12, 8)
        }
        controlPanel = rootPanel

        statusText = TextView(this).apply {
            text = "1-й ход: нажми свою фигуру и клетку (я сдублирую в игру)"
            setTextColor(Color.rgb(120, 255, 160))
            textSize = 12.5f
            setPadding(6, 2, 6, 6)
        }
        rootPanel.addView(statusText)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun makeBtn(label: String, bg: Int = Color.rgb(45, 62, 78)): Button {
            return Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(bg)
                setPadding(10, 4, 10, 4)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(3, 2, 3, 2)
                layoutParams = lp
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

        val btnReset = makeBtn("🔄 Новая").apply {
            setOnClickListener { resetBoardState() }
        }

        val btnAuto = makeBtn("Авто: ВЫКЛ").apply {
            setOnClickListener {
                autoPlayEnabled = !autoPlayEnabled
                text = if (autoPlayEnabled) "Авто: ВКЛ" else "Авто: ВЫКЛ"
                setBackgroundColor(if (autoPlayEnabled) Color.rgb(30, 130, 70) else Color.rgb(45, 62, 78))
                if (autoPlayEnabled && gamePhase == 2 && currentBestMoveUci.length >= 4) {
                    executeMoveOnBothBoards(currentBestMoveUci, isBotMove = true)
                }
            }
        }

        val btnBest = makeBtn("▶️ Сходить", Color.rgb(35, 140, 75)).apply {
            setOnClickListener {
                if (currentBestMoveUci.length >= 4) {
                    executeMoveOnBothBoards(currentBestMoveUci, isBotMove = true)
                } else {
                    runEngineSuggestion()
                }
            }
        }
        btnPlayBest = btnBest

        val btnEdit = makeBtn("🎮 Игра").apply {
            setOnClickListener {
                isFreeEditMode = !isFreeEditMode
                isDeleteMode = false
                selectedCell = null
                boardOverlay?.selectedBoardCell = null
                text = if (isFreeEditMode) "✏️ Ред." else "🎮 Игра"
                setBackgroundColor(if (isFreeEditMode) Color.rgb(160, 105, 20) else Color.rgb(45, 62, 78))
                statusText?.text = if (isFreeEditMode) {
                    "✏️ Свободная перестановка: двигай любую фигуру без хода в игре"
                } else {
                    "🎮 Режим игры активен"
                }
                boardOverlay?.invalidate()
            }
        }

        val btnDel = makeBtn("🗑 Стереть").apply {
            setOnClickListener {
                isDeleteMode = !isDeleteMode
                selectedCell = null
                boardOverlay?.selectedBoardCell = null
                setBackgroundColor(if (isDeleteMode) Color.rgb(180, 45, 45) else Color.rgb(45, 62, 78))
                statusText?.text = if (isDeleteMode) "Тапни по фигуре, чтобы убрать её с доски бота" else "Удаление выключено"
            }
        }

        val btnPass = makeBtn("✋ Доска").apply {
            setOnClickListener {
                setOverlayPassThrough(!passThroughTouch)
            }
        }
        btnPassThrough = btnPass

        val btnClose = makeBtn("✖", Color.rgb(150, 40, 40)).apply {
            layoutParams = LinearLayout.LayoutParams(90, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(3, 2, 3, 2)
            }
            setOnClickListener { stopSelf() }
        }

        row1.addView(btnSide)
        row1.addView(btnReset)
        row1.addView(btnAuto)
        row1.addView(btnBest)

        row2.addView(btnEdit)
        row2.addView(btnDel)
        row2.addView(btnPass)
        row2.addView(btnClose)

        rootPanel.addView(row1)
        rootPanel.addView(row2)

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = (screenHeight * 0.11f).toInt()
        }

        windowManager.addView(rootPanel, panelParams)
    }

    private fun setOverlayPassThrough(passThrough: Boolean) {
        passThroughTouch = passThrough
        val view = boardOverlay ?: return
        val params = overlayParams ?: return
        view.touchInteractive = !passThrough
        if (passThrough) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            btnPassThrough?.text = "👁 Сквозь"
            btnPassThrough?.setBackgroundColor(Color.rgb(140, 80, 20))
            statusText?.text = "👁 Клики проходят сквозь оверлей прямо в Duolingo"
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            btnPassThrough?.text = "✋ Доска"
            btnPassThrough?.setBackgroundColor(Color.rgb(45, 62, 78))
        }
        windowManager.updateViewLayout(view, params)
    }

    private fun resetBoardState() {
        internalBoard = createInitialBoard()
        selectedCell = null
        currentBestMoveUci = ""
        gamePhase = 0 // Всегда ждём 1-й ход!
        boardOverlay?.detectedBoard = internalBoard
        boardOverlay?.selectedBoardCell = null
        boardOverlay?.lastMovedCells = null
        boardOverlay?.bestMoveUci = null
        boardOverlay?.ponderMoveUci = null
        boardOverlay?.invalidate()

        val sideStr = if (playerIsWhite) "Белыми" else "Чёрными"
        statusText?.text = "⏳ 1-й ход ($sideStr): укажи свой 1-й ход (или ход врага) на доске"
    }

    private fun onUserTappedBoard(r: Int, c: Int) {
        if (isExecutingGesture) return

        // Режим удаления фигуры
        if (isDeleteMode) {
            internalBoard[r][c] = '.'
            isDeleteMode = false
            boardOverlay?.invalidate()
            statusText?.text = "Фигура удалена. Продолжаем."
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
            statusText?.text = "Выбрана ${getPieceName(piece)} на $sqName ➔ тапни куда пойти"
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

        // Если тапнули на другую фигуру того же цвета (и не в режиме редактирования) — переключаем выбор на неё
        if (!isFreeEditMode && targetPiece != '.' && targetPiece.isUpperCase() == movingPiece.isUpperCase()) {
            selectedCell = r to c
            boardOverlay?.selectedBoardCell = r to c
            boardOverlay?.invalidate()
            val sqName = "${('a' + c)}${8 - r}"
            statusText?.text = "Переключено на ${getPieceName(targetPiece)} ($sqName) ➔ куда пойти?"
            return
        }

        selectedCell = null
        boardOverlay?.selectedBoardCell = null

        // Если включён режим свободной редакции — просто переставляем на доске бота без клика в игру
        if (isFreeEditMode) {
            applyMoveToInternalBoard(fromR, fromC, r, c)
            boardOverlay?.invalidate()
            statusText?.text = "✏️ Переставлено на доске бота"
            return
        }

        val uci = toUci(fromR, fromC, r, c, movingPiece)
        val isPlayerPiece = movingPiece.isUpperCase() == playerIsWhite

        if (isPlayerPiece) {
            // Игрок сделал СВОЙ ход на доске бота -> дублируем его в Duolingo и ждём ответ соперника!
            executeMoveOnBothBoards(uci, isBotMove = false)
        } else {
            // Игрок вручную указал ход СОПЕРНИКА (Оскара) на доске бота -> обновляем доску и сразу считаем лучший ответ!
            applyMoveToInternalBoard(fromR, fromC, r, c)
            boardOverlay?.lastMovedCells = (fromR to fromC) to (r to c)
            boardOverlay?.invalidate()
            gamePhase = 2
            runEngineSuggestion()
        }
    }

    private fun executeMoveOnBothBoards(uci: String, isBotMove: Boolean) {
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

        val svc = AutoMoveService.instance
        if (svc == null) {
            gamePhase = 1
            statusText?.text = "⚠️ Включи Спец. возможности! Ход $uci записан. Жду ход соперника..."
            return
        }

        isExecutingGesture = true
        val overlay = boardOverlay ?: return
        val params = overlayParams ?: return

        // Временно делаем оверлей прозрачным для тачей, чтобы жест AutoMoveService попал точно в Duolingo
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager.updateViewLayout(overlay, params)

        statusText?.text = "🤖 Дублирую ход $uci на игровую доску..."

        svc.performChessMove(
            uciMove = uci,
            boardLeft = overlay.boardLeftPx,
            boardTop = overlay.boardTopPx,
            boardSize = overlay.boardSizePx,
            whiteAtBottom = playerIsWhite
        ) {
            mainHandler.postDelayed({
                if (!passThroughTouch) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    windowManager.updateViewLayout(overlay, params)
                }
                isExecutingGesture = false
                gamePhase = 1
                statusText?.text = "👀 Жду ответ соперника (сходит сам или укажи его ход на доске)..."
            }, 250L)
        }
    }

    private fun startOpponentWatcherLoop() {
        watcherJob?.cancel()
        watcherJob = serviceScope.launch {
            while (isActive) {
                delay(450L)
                // Автоматически ловим ответный ход соперника в фазе 1
                if (gamePhase != 1 || isExecutingGesture || isFreeEditMode) continue

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
                    statusText?.text = "⚡ Соперник сходил $uci! Считаю лучший ответ..."
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
                statusText?.text = "💡 Лучший ход: ${result.bestMove} ($sign${result.evalCp / 100f}) ➔ нажми [▶️ Сходить]"

                if (autoPlayEnabled) {
                    delay(300L)
                    executeMoveOnBothBoards(result.bestMove, isBotMove = true)
                }
            }
        }
    }

    private fun applyMoveToInternalBoard(fromR: Int, fromC: Int, toR: Int, toC: Int) {
        val piece = internalBoard[fromR][fromC]
        internalBoard[fromR][fromC] = '.'

        // Авто-превращение пешки в ферзя на последней горизонтали
        val finalPiece = when {
            piece == 'P' && toR == 0 -> 'Q'
            piece == 'p' && toR == 7 -> 'q'
            else -> piece
        }
        internalBoard[toR][toC] = finalPiece

        // Авто-рокировка ладьи, если король прыгнул на 2 клетки
        if ((piece == 'K' || piece == 'k') && kotlin.math.abs(toC - fromC) == 2) {
            if (toC == 6) { // Короткая рокировка (g1/g8)
                internalBoard[toR][5] = internalBoard[toR][7]
                internalBoard[toR][7] = '.'
            } else if (toC == 2) { // Длинная рокировка (c1/c8)
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
