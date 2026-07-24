package com.linkbubble.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.linkbubble.app.databinding.ActivityMainBinding
import com.linkbubble.app.service.BubbleService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnStartBubble.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                val serviceIntent = Intent(this, BubbleService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this, "Burbuja iniciada", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Primero concede el permiso de superposición",
                    Toast.LENGTH_LONG
                ).show()
            }
            updateStatus()
        }

        binding.btnStopBubble.setOnClickListener {
            stopService(Intent(this, BubbleService::class.java))
            Toast.makeText(this, "Burbuja detenida", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val granted = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = if (granted) {
            "Permiso de superposición: concedido ✅"
        } else {
            "Permiso de superposición: NO concedido ❌"
        }
    }
}
