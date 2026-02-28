package com.swaran.airbridge.core.data.logging

import android.util.Log
import com.swaran.airbridge.core.common.logging.AirLogger
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Timber-backed implementation of [AirLogger] with hierarchical tags.
 *
 * Tag format: `AirLogger/ClassName/methodName`
 *
 * Example output:
 * ```
 * D/AirLogger/UploadScheduler/handleUpload: [upload-123] Starting upload
 * ```
 */
@Singleton
class TimberAirLogger @Inject constructor() : AirLogger {

    companion object {
        fun plantDebugTree() {
            if (Timber.forest().isEmpty()) {
                Timber.plant(Timber.DebugTree())
            }
        }

        private fun buildTag(classTag: String, methodTag: String): String {
            return "${AirLogger.COMMON_TAG}/$classTag/$methodTag"
        }
    }

    override fun log(priority: Int, classTag: String, methodTag: String, message: String, vararg args: Any?) {
        val fullTag = buildTag(classTag, methodTag)
        Timber.tag(fullTag)
        when (priority) {
            Log.VERBOSE -> Timber.v(message, *args)
            Log.DEBUG -> Timber.d(message, *args)
            Log.INFO -> Timber.i(message, *args)
            Log.WARN -> Timber.w(message, *args)
            Log.ERROR -> Timber.e(message, *args)
            Log.ASSERT -> Timber.wtf(message, *args)
        }
    }

    override fun log(priority: Int, classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {
        val fullTag = buildTag(classTag, methodTag)
        Timber.tag(fullTag)
        when (priority) {
            Log.VERBOSE -> Timber.v(t, message, *args)
            Log.DEBUG -> Timber.d(t, message, *args)
            Log.INFO -> Timber.i(t, message, *args)
            Log.WARN -> Timber.w(t, message, *args)
            Log.ERROR -> Timber.e(t, message, *args)
            Log.ASSERT -> Timber.wtf(t, message, *args)
        }
    }

    override fun v(classTag: String, methodTag: String, message: String, vararg args: Any?) =
        log(Log.VERBOSE, classTag, methodTag, message, *args)
    override fun v(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) =
        log(Log.VERBOSE, classTag, methodTag, t, message, *args)

    override fun d(classTag: String, methodTag: String, message: String, vararg args: Any?) =
        log(Log.DEBUG, classTag, methodTag, message, *args)
    override fun d(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) =
        log(Log.DEBUG, classTag, methodTag, t, message, *args)

    override fun i(classTag: String, methodTag: String, message: String, vararg args: Any?) =
        log(Log.INFO, classTag, methodTag, message, *args)
    override fun i(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) =
        log(Log.INFO, classTag, methodTag, t, message, *args)

    override fun w(classTag: String, methodTag: String, message: String, vararg args: Any?) =
        log(Log.WARN, classTag, methodTag, message, *args)
    override fun w(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) =
        log(Log.WARN, classTag, methodTag, t, message, *args)

    override fun e(classTag: String, methodTag: String, message: String, vararg args: Any?) =
        log(Log.ERROR, classTag, methodTag, message, *args)
    override fun e(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) =
        log(Log.ERROR, classTag, methodTag, t, message, *args)

    override fun wtf(classTag: String, methodTag: String, message: String, vararg args: Any?) =
        log(Log.ASSERT, classTag, methodTag, message, *args)
    override fun wtf(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) =
        log(Log.ASSERT, classTag, methodTag, t, message, *args)
}
