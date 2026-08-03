package com.soryx.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class RemoteHttpServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        // NanoHTTPD doesn't set TCP_NODELAY on accepted sockets, so Nagle's algorithm
        // combined with delayed ACKs was adding a ~200-250ms stall to every single
        // request regardless of what it did (even a plain static GET /) — this was the
        // real bottleneck, not su/input.
        setServerSocketFactory {
            object : ServerSocket() {
                override fun accept(): Socket {
                    val socket = super.accept()
                    socket.tcpNoDelay = true
                    return socket
                }
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parms

        return try {
            when (uri) {
                "/" -> serveAsset("remote.html", "text/html")
                "/documentacao" -> serveAsset("manual.html", "text/html")
                "/manifest.json" -> serveAsset("manifest.json", "application/manifest+json")
                "/sw.js" -> serveAsset("sw.js", "application/javascript")
                "/icon-192.png" -> serveBinaryAsset("icon-192.png", "image/png")
                "/icon-512.png" -> serveBinaryAsset("icon-512.png", "image/png")
                "/key" -> handleKey(params["code"])
                "/text" -> handleText(params["value"])
                "/volume" -> handleVolume(params["dir"])
                "/mouse/move" -> handleMouseMove(params["dx"], params["dy"])
                "/apps" -> handleApps()
                "/apps/icon" -> handleAppIcon(params["package"])
                "/apps/launch" -> handleAppLaunch(params["package"])
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request $uri", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun serveAsset(name: String, mime: String): Response {
        val input = context.assets.open(name)
        val text = BufferedReader(InputStreamReader(input)).use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, mime, text)
    }

    private fun serveBinaryAsset(name: String, mime: String): Response {
        val afd = context.assets.openFd(name)
        return newFixedLengthResponse(Response.Status.OK, mime, afd.createInputStream(), afd.length)
    }

    private fun handleKey(code: String?): Response {
        val keyCode = code?.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing code")

        // Rooted boxes (daemon, then su as backup) take priority since they're strictly
        // more capable; Accessibility only kicks in when neither is available (no su).
        if (!InputDaemonClient.sendKeyEvent(keyCode) && !SoryxAccessibilityService.tryKeyEvent(keyCode)) {
            InputDaemonLauncher.ensureRunning(context)
            RootShell.exec("input keyevent $keyCode")
        }
        return ok()
    }

    private fun handleText(value: String?): Response {
        if (value.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing value")
        }
        if (!InputDaemonClient.sendText(value) && !SoryxAccessibilityService.tryText(value)) {
            InputDaemonLauncher.ensureRunning(context)
            val escaped = value.replace("\\", "\\\\").replace("'", "'\\''")
            RootShell.exec("input text '$escaped'")
        }
        return ok()
    }

    private fun handleVolume(direction: String?): Response {
        val adjust = when (direction) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid direction")
        }
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
        return ok()
    }

    private fun handleMouseMove(dxParam: String?, dyParam: String?): Response {
        val dx = dxParam?.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing dx")
        val dy = dyParam?.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing dy")

        RootShell.exec("input roll $dx $dy")
        return ok()
    }

    private fun handleApps(): Response {
        val pm = context.packageManager
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        var resolved = pm.queryIntentActivities(leanback, 0)
        if (resolved.isEmpty()) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            resolved = pm.queryIntentActivities(launcher, 0)
        }

        val apps = resolved
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }

        val array = JSONArray()
        for ((pkg, label) in apps) {
            array.put(JSONObject().put("package", pkg).put("label", label))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", array.toString())
    }

    private fun handleAppIcon(pkg: String?): Response {
        if (pkg.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing package")
        }
        return try {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            val bytes = drawableToPng(drawable)
            newFixedLengthResponse(
                Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong()
            )
        } catch (e: PackageManager.NameNotFoundException) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Icon not found")
        }
    }

    private fun handleAppLaunch(pkg: String?): Response {
        if (pkg.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing package")
        }
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "App not launchable")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ok()
    }

    private fun drawableToPng(drawable: Drawable): ByteArray {
        val bitmap = if (drawable is BitmapDrawable) {
            drawable.bitmap
        } else {
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun ok(): Response = newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")

    companion object {
        private const val TAG = "RemoteHttpServer"
    }
}
