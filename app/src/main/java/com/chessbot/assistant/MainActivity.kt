package com.chessbot.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private var selectedGameMode: String = "DUOLINGO"

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ChessOverlayService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA_INTENT", result.data)
                putExtra("GAME_MODE", selectedGameMode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish()
        } else {
            Toast.makeText(this, "Захват экрана отменён", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Разрешение Оверлея уже включено!", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnAccessibilityPerm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnStartDuolingo).setOnClickListener {
            startBotWithMode("DUOLINGO")
        }

        findViewById<Button>(R.id.btnStartChessCom).setOnClickListener {
            startBotWithMode("CHESS_COM")
        }
    }

    private fun startBotWithMode(mode: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала включи пункт 1 (Поверх других окон)!", Toast.LENGTH_LONG).show()
            return
        }
        selectedGameMode = mode
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    override fun onResume() {
        super.onResume()
        updatePermStatus()
    }

    private fun updatePermStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accOk = AutoMoveService.instance != null
        tvStatus.text = "Статус разрешений:\n" +
                "• Оверлей (рисование стрелок): ${if (overlayOk) "✅ ВКЛ" else "❌ ВЫКЛ"}\n" +
                "• Accessibility (авто-ходы): ${if (accOk) "✅ ВКЛ" else "⚠️ ВЫКЛ (нажми п.2)"}"
    }
}
