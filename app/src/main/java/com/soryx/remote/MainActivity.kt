package com.soryx.remote

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var lastIp: String? = null
    private lateinit var urlText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var accessibilityWarningText: TextView

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshQrCode()
            refreshAccessibilityWarning()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val serviceIntent = Intent(this, RemoteServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        urlText = findViewById(R.id.urlText)
        qrImage = findViewById(R.id.qrImage)
        accessibilityWarningText = findViewById(R.id.accessibilityWarningText)

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        lastIp = null
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun refreshQrCode() {
        val ip = NetworkUtil.getLocalIpAddress()

        if (ip == null) {
            urlText.text = "Não foi possível detectar o IP local. Verifique a conexão de rede."
            return
        }

        if (ip == lastIp) return
        lastIp = ip

        val url = "http://$ip:${RemoteServerService.PORT}/"
        urlText.text = url
        qrImage.setImageBitmap(QrCodeUtil.generate(url))
    }

    private fun refreshAccessibilityWarning() {
        accessibilityWarningText.visibility = if (isAccessibilityServiceEnabled()) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${SoryxAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
        for (component in splitter) {
            if (component.equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 5000L
    }
}
