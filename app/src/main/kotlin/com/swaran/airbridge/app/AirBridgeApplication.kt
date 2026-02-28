package com.swaran.airbridge.app

import android.app.Application
import com.swaran.airbridge.core.data.logging.TimberAirLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AirBridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize Timber for debug logging
        TimberAirLogger.plantDebugTree()
    }
}
