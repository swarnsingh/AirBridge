package com.swaran.airbridge.app

import android.app.Application
import android.os.Build
import android.os.StrictMode
import com.swaran.airbridge.core.data.logging.TimberAirLogger
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class AirBridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber for debug logging
        TimberAirLogger.plantDebugTree()
        
        // Enable StrictMode in debug builds to catch performance issues
        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }
    }
    
    private fun enableStrictMode() {
        // Thread policy - detect disk/network operations on main thread
        val threadPolicy = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectResourceMismatches()
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detectUnbufferedIo()
                }
            }
            .penaltyLog() // Log violations to logcat
            .penaltyFlashScreen() // Flash screen on violation
            .build()
        
        StrictMode.setThreadPolicy(threadPolicy)
        
        // VM policy - detect memory leaks and other issues
        val vmPolicy = StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .detectActivityLeaks()
            .detectContentUriWithoutPermission()
            .detectFileUriExposure()
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detectUntaggedSockets()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    detectIncorrectContextUse()
                    detectNonSdkApiUsage()
                }
            }
            .penaltyLog()
            .build()
        
        StrictMode.setVmPolicy(vmPolicy)
        
        Timber.i("StrictMode enabled for DEBUG build")
    }
}
