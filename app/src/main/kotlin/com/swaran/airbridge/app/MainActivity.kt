package com.swaran.airbridge.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.swaran.airbridge.app.ui.theme.AirBridgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLink(intent)
        setContent {
            AirBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return

        if (data.scheme == "airbridge" && data.host == "pair") {
            val pairingId = data.getQueryParameter("id")
            val serverUrl = data.getQueryParameter("server")

            if (!pairingId.isNullOrEmpty() && !serverUrl.isNullOrEmpty()) {
                Thread {
                    try {
                        val url = URL("$serverUrl/api/pair/approve")
                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                        }

                        val body = """{"pairingId":"$pairingId"}"""
                        connection.outputStream.use { os ->
                            OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                                writer.write(body)
                                writer.flush()
                            }
                        }

                        val code = connection.responseCode
                        runOnUiThread {
                            if (code in 200..299) {
                                Toast.makeText(
                                    this,
                                    "Browser paired successfully",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this,
                                    "Pairing failed ($code)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Pairing failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }.start()
            }
        }
    }
}
