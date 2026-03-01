package com.swaran.airbridge.core.data.di

import android.content.Context
import com.swaran.airbridge.core.data.db.AirBridgeDatabase
import com.swaran.airbridge.core.data.db.dao.UploadQueueDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for database dependencies.
 *
 * Provides:
 * - AirBridgeDatabase singleton
 * - UploadQueueDao
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AirBridgeDatabase {
        return AirBridgeDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideUploadQueueDao(database: AirBridgeDatabase): UploadQueueDao {
        return database.uploadQueueDao()
    }
}