package com.swaran.airbridge.core.service

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server preferences for AirBridge service configuration.
 *
 * This class manages server-related preferences such as:
 * - Last used port
 * - Session token persistence
 * - Feature flags
 */
@Singleton
class ServerPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "airbridge_server_prefs"
        private const val KEY_LAST_PORT = "last_server_port"
        private const val DEFAULT_PORT = 8081
    }

    /**
     * Saves the last successfully used server port.
     */
    fun saveLastPort(port: Int) {
        prefs.edit().putInt(KEY_LAST_PORT, port).apply()
    }

    /**
     * Gets the last used server port, or default if none saved.
     */
    fun getLastPort(): Int {
        return prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT)
    }

    /**
     * Clears all server preferences.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
