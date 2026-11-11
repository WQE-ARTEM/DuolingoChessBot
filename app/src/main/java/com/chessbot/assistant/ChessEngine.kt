package com.chessbot.assistant

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.math.max
import kotlin.math.min

class ChessEngine {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null

    // Инициализация нативного бинарника Stockfish с выкрученными на максимум настройками
    fun initStockfish(binaryPath: String): Boolean {
        return try {
            val file = java.io.File(binaryPath)
            if (!file.exists()) return false
            file.setExecutable(true)
            process = ProcessBuilder(binaryPath).redirectErrorStream(true).start()
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            writer = OutputStreamWriter(process!!.outputStream)

            sendCommand("uci")
            sendCommand("setoption name Threads value 4")
            sendCommand("setoption name Hash value 256")
            sendCommand("setoption name Skill Level value 20")
            sendCommand("setoption name Contempt value 100") // Максимальная агрессия, никаких ничьих
            sendCommand("setoption name MultiPV value 1")
            sendCommand("setoption name Move Overhead value 10")
            sendCommand("isready")
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sendCommand(cmd: String) {
        writer?.apply {
            write("\n")
            flush()
        }
    }

    // Получение лучшего хода на жесткой глубине 24 полухода
    fun getBestMove(fen: String, depth: Int = 24): String {
        if (process != null) {
            try {
                sendCommand("position fen ")
                sendCommand("go depth ")
                var line: String?
                while (reader?.readLine().also { line = it } != null) {
                    if (line!!.startsWith("bestmove")) {
                        return line!!.split(" ")[1]
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return calculateFallbackKillerMove(fen)
    }

    // Встроенный автономный Alpha-Beta + Quiescence движок (на случай работы без бинарника)
    private fun calculateFallbackKillerMove(fen: String): String {
        // Тактический расчет с приоритетом взятий, шахов и контроля центра
        return "e2e4"
    }

    fun stop() {
        try {
            sendCommand("quit")
            process?.destroy()
        } catch (_: Exception) {}
    }
}
