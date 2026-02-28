package com.swaran.airbridge.core.common.logging

/**
 * Abstraction over logging that supports both local (Timber) and future cloud backends.
 *
 * Hierarchical tag structure: `AirLogger/ClassName/methodName`
 *
 * Usage:
 * ```kotlin
 * class MyClass @Inject constructor(
 *     private val logger: AirLogger
 * ) {
 *     companion object {
 *         private const val CLASS_TAG = "MyClass"
 *     }
 *
 *     fun doSomething() {
 *         logger.d(CLASS_TAG, "doSomething", "Doing something with $value")
 *     }
 * }
 * ```
 * Output: `D/AirLogger/MyClass/doSomething: Doing something with ...`
 *
 * Future cloud integration: Implement [AirLogger.CloudBackend] and add to [AirLoggerFactory].
 */
interface AirLogger {

    /**
     * Log with hierarchical tags.
     *
     * @param priority One of [android.util.Log] constants (VERBOSE, DEBUG, INFO, WARN, ERROR, ASSERT)
     * @param classTag The class name (e.g., "UploadScheduler")
     * @param methodTag The method name (e.g., "handleUpload")
     * @param message The log message with optional format args
     * @param args Format arguments for message
     */
    fun log(priority: Int, classTag: String, methodTag: String, message: String, vararg args: Any?)

    /**
     * Log with throwable.
     *
     * @param priority One of [android.util.Log] constants
     * @param classTag The class name
     * @param methodTag The method name
     * @param t Throwable to log
     * @param message The log message
     * @param args Format arguments
     */
    fun log(priority: Int, classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?)

    // Convenience methods with hierarchical tags
    fun v(classTag: String, methodTag: String, message: String, vararg args: Any?)
    fun v(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?)

    fun d(classTag: String, methodTag: String, message: String, vararg args: Any?)
    fun d(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?)

    fun i(classTag: String, methodTag: String, message: String, vararg args: Any?)
    fun i(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?)

    fun w(classTag: String, methodTag: String, message: String, vararg args: Any?)
    fun w(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?)

    fun e(classTag: String, methodTag: String, message: String, vararg args: Any?)
    fun e(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?)

    fun wtf(classTag: String, methodTag: String, message: String, vararg args: Any?)
    fun wtf(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?)

    /**
     * Cloud backend interface for future remote logging.
     */
    interface CloudBackend {
        suspend fun log(level: LogLevel, fullTag: String, message: String, throwable: Throwable?)
        suspend fun flush()
    }

    enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, ASSERT }

    companion object {
        const val COMMON_TAG = "AirLogger"
    }
}
