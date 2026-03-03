package com.swaran.airbridge.core.common.di

import javax.inject.Qualifier

/**
 * Qualifier for the application-wide [kotlinx.coroutines.CoroutineScope] that outlives
 * individual request scopes. Used for background tasks like resume deadline watchers.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
