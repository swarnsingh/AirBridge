package com.swaran.airbridge.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swaran.airbridge.app.pairing.PairingViewModel
import com.swaran.airbridge.app.ui.theme.AirBridgeTheme
import com.swaran.airbridge.core.common.logging.AirLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var logger: AirLogger

    private val pairingViewModel: PairingViewModel by viewModels()

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Collect pairing state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pairingViewModel.state.collect { state ->
                    handlePairingState(state)
                }
            }
        }
        
        handleDeepLink(intent)
        
        setContent {
            AirBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AirBridgeApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handlePairingState(state: PairingViewModel.PairingState) {
        when (state) {
            is PairingViewModel.PairingState.StartingServer -> {
                logger.d(TAG, "handlePairingState", "Starting server for pairing...")
            }
            is PairingViewModel.PairingState.Approving -> {
                logger.d(TAG, "handlePairingState", "Sending pairing approval...")
            }
            is PairingViewModel.PairingState.Success -> {
                logger.d(TAG, "handlePairingState", "Pairing successful")
                Toast.makeText(this, "Browser paired successfully", Toast.LENGTH_SHORT).show()
                pairingViewModel.resetState()
            }
            is PairingViewModel.PairingState.Error -> {
                logger.e(TAG, "handlePairingState", "Pairing failed: ${state.message}")
                Toast.makeText(this, "Pairing failed: ${state.message}", Toast.LENGTH_LONG).show()
                pairingViewModel.resetState()
            }
            else -> { /* Idle - no action */ }
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return

        logger.d(TAG, "log", "Deep link received: $data")

        if (data.scheme == "airbridge" && data.host == "pair") {
            val pairingId = data.getQueryParameter("id")
            val serverUrl = data.getQueryParameter("server")

            logger.d(TAG, "log", "Pairing ID: $pairingId, Server URL: $serverUrl")

            if (!pairingId.isNullOrEmpty() && !serverUrl.isNullOrEmpty()) {
                pairingViewModel.approvePairing(pairingId, serverUrl)
            } else {
                logger.w(TAG, "log", "Missing pairingId or serverUrl")
            }
        }
    }
}
