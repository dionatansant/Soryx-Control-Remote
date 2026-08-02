package com.soryx.remote

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val serviceIntent = Intent(this, RemoteServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val ip = NetworkUtil.getLocalIpAddress()
        val urlText = findViewById<TextView>(R.id.urlText)
        val qrImage = findViewById<ImageView>(R.id.qrImage)

        if (ip == null) {
            urlText.text = "Não foi possível detectar o IP local. Verifique a conexão de rede."
            return
        }

        val url = "http://$ip:${RemoteServerService.PORT}/"
        urlText.text = url
        qrImage.setImageBitmap(QrCodeUtil.generate(url))
    }
}
