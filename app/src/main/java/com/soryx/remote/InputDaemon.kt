package com.soryx.remote

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

const val INPUT_DAEMON_PORT = 18732

/**
 * Entry point run standalone via `app_process` as root (see InputDaemonLauncher).
 * Stays resident so key/text injection skips the ~200-300ms cost of spawning a
 * fresh process (Zygote fork + framework class load) on every single command,
 * which is what made every button except volume (handled in-process via
 * AudioManager) feel slow.
 */
fun main(args: Array<String>) {
    val server = ServerSocket(INPUT_DAEMON_PORT, 50, InetAddress.getByName("127.0.0.1"))
    while (true) {
        val client = try {
            server.accept()
        } catch (_: Exception) {
            continue
        }
        Thread { handleDaemonClient(client) }.start()
    }
}

private fun handleDaemonClient(client: Socket) {
    try {
        client.soTimeout = 2000
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val writer = PrintWriter(client.getOutputStream(), true)
        val line = reader.readLine() ?: return
        val spaceIdx = line.indexOf(' ')
        val cmd = if (spaceIdx == -1) line else line.substring(0, spaceIdx)
        val arg = if (spaceIdx == -1) null else line.substring(spaceIdx + 1)

        when (cmd) {
            "K" -> arg?.toIntOrNull()?.let { InputInjector.injectKeyEvent(it) }
            "T" -> arg?.let {
                val text = String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8)
                InputInjector.injectText(text)
            }
        }
        writer.println("OK")
    } catch (_: Exception) {
    } finally {
        try {
            client.close()
        } catch (_: Exception) {
        }
    }
}
