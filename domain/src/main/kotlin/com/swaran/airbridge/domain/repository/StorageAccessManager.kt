package com.swaran.airbridge.domain.repository

interface StorageAccessManager {
    fun setStorageDirectory(uriString: String)
    fun resetToDefaultDirectory()
    fun getStorageDirectoryUri(): String
    fun getStorageDirectoryName(): String
    fun hasCustomStorageDirectory(): Boolean
}
