package com.swaran.airbridge.domain.repository

interface StorageAccessManager {
    fun setStorageDirectory(uriString: String)
    fun hasStorageDirectory(): Boolean
}
