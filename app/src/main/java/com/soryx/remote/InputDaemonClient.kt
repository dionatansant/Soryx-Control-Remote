package com.soryx.remote

import android.util.Base64
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/** Talks to the resident root daemon (InputDaemon.kt). Fails fast if it isn't up yet. */
object InputDaemonClient {
    private const val HOST = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MS = 200
    private const val READ_TIMEOUT_MS = 500

    fun sendKeyEvent(code: Int): Boolean = send("K $code")

    fun sendText(text: String): Boolean {
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return send("T $encoded")
    }

    private fun send(line: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(HOST, INPUT_DAEMON_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                socket.getOutputStream().write((line + "\n").toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                val response = socket.getInputStream().bufferedReader().readLine()
                response == "OK"
            }
        } catch (e: Exception) {
            Log.e("InputDaemonClient", "send failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
