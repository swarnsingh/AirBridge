package com.swaran.airbridge.core.service

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun startServer() {
        val intent = Intent(context, ForegroundServerService::class.java).apply {
            action = ForegroundServerService.ACTION_START
        }
        context.startForegroundService(intent)
    }

    fun stopServer() {
        val intent = Intent(context, ForegroundServerService::class.java).apply {
            action = ForegroundServerService.ACTION_STOP
        }
        context.startService(intent)
    }
}
