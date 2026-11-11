package com.chessbot.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ChessOverlayService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA_INTENT", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            Toast.makeText(this, "ChessBot запущен! Откройте Duolingo Chess.", Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, "Разрешение на захват экрана отклонено", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(19, 31, 36))
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "♟️ Duolingo Chess Bot\n(Overlay + Auto-Move)"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(200, 220, 230))
            setPadding(0, 0, 0, 40)
        }
        root.addView(statusView)

        root.addView(Button(this).apply {
            text = "1. Разрешить Поверх других окон (Overlay)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "Уже разрешено!", Toast.LENGTH_SHORT).show()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "2. Включить Авто-кликер (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(Button(this).apply {
            text = "🚀 3. ЗАПУСТИТЬ БОТА"
            setBackgroundColor(Color.rgb(88, 204, 2))
            setTextColor(Color.BLACK)
            textSize = 16f
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Сначала выдайте разрешение №1 (Overlay)!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpManager.createScreenCaptureIntent())
            }
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val overlayOk = Settings.canDrawOverlays(this)
        val accOk = AutoMoveService.instance != null
        statusView.text = "Статус разрешений:\n" +
                "• Оверлей (рисование стрелок): ${if (overlayOk) "✅ ВКЛ" else "❌ ВЫКЛ"}\n" +
                "• Accessibility (авто-ходы): ${if (accOk) "✅ ВКЛ" else "⚠️ ВЫКЛ (работают только подсказки)"}"
    }
}
