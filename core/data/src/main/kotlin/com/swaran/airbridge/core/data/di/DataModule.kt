package com.swaran.airbridge.core.data.di

import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.core.service.ServerRepositoryImpl
import com.swaran.airbridge.core.storage.repository.FileRepository
import com.swaran.airbridge.domain.repository.ServerRepository
import com.swaran.airbridge.domain.repository.SessionRepository
import com.swaran.airbridge.domain.repository.StorageAccessManager
import com.swaran.airbridge.domain.repository.StorageRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindServerRepository(
        impl: ServerRepositoryImpl
    ): ServerRepository

    @Binds
    @Singleton
    abstract fun bindStorageRepository(
        impl: FileRepository
    ): StorageRepository

    @Binds
    @Singleton
    abstract fun bindStorageAccessManager(
        impl: FileRepository
    ): StorageAccessManager

    @Binds
    @Singleton
    abstract fun bindSessionRepository(
        impl: SessionTokenManager
    ): SessionRepository
}
