package com.linkbubble.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.linkbubble.app.databinding.ActivityMainBinding
import com.linkbubble.app.service.BubbleService

class MainActivity : AppCompatActivity() {

    // Web Client ID de Firebase (Authentication > Sign-in method > Google > ID de cliente web).
    private val webClientId =
        "1063843629354-drupuj44cpm4r1lforpn8sbp4iv0otj4.apps.googleusercontent.com"

    private lateinit var binding: ActivityMainBinding
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential).addOnCompleteListener { authResult ->
                if (authResult.isSuccessful) {
                    Toast.makeText(this, "Sesión iniciada ✅", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No se pudo iniciar sesión", Toast.LENGTH_LONG).show()
                }
                updateAccountStatus()
            }
        } catch (e: ApiException) {
            Toast.makeText(this, "Login cancelado o falló: ${e.statusCode}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        updateAccountStatus()

        binding.btnGoogleSignIn.setOnClickListener {
            if (auth.currentUser != null) {
                // Ya hay sesión iniciada: este toque cierra sesión.
                auth.signOut()
                googleSignInClient.signOut()
                Toast.makeText(this, "Sesión cerrada", Toast.LENGTH_SHORT).show()
                updateAccountStatus()
            } else {
                signInLauncher.launch(googleSignInClient.signInIntent)
            }
        }

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
        updateAccountStatus()
    }

    private fun updateStatus() {
        val granted = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = if (granted) {
            "Permiso de superposición: concedido ✅"
        } else {
            "Permiso de superposición: NO concedido ❌"
        }
    }

    private fun updateAccountStatus() {
        val user = auth.currentUser
        if (user != null) {
            binding.tvAccountStatus.text = "Sesión: ${user.email}"
            binding.btnGoogleSignIn.text = "Cerrar sesión"
        } else {
            binding.tvAccountStatus.text = "Sesión: no iniciada"
            binding.btnGoogleSignIn.text = "Iniciar sesión con Google"
        }
    }
}
