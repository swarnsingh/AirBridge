package com.swaran.airbridge.domain.repository

/**
 * Manager for storage directory selection and configuration.
 *
 * Handles user preferences for storage location:
 * - Default: Downloads/AirBridge/
 * - Custom: User-selected directory via SAF
 *
 * This interface abstracts the persistence of storage preferences,
 * allowing the domain layer to remain agnostic of SharedPreferences/ DataStore details.
 *
 * @see com.swaran.airbridge.core.data.repository.StorageAccessManagerImpl Implementation
 */
interface StorageAccessManager {

    /**
     * Set a custom storage directory via SAF URI.
     *
     * @param uriString SAF tree URI (e.g., "content://com.android.externalstorage.documents/tree/primary%3ADocuments")
     */
    fun setStorageDirectory(uriString: String)

    /**
     * Reset to default storage directory (Downloads/AirBridge).
     */
    fun resetToDefaultDirectory()

    /**
     * Get the currently configured storage directory URI.
     *
     * @return URI string, or default if none set
     */
    fun getStorageDirectoryUri(): String

    /**
     * Get display name of the storage directory.
     *
     * @return Human-readable name (e.g., "Downloads/AirBridge" or "My Folder")
     */
    fun getStorageDirectoryName(): String

    /**
     * Check if user has selected a custom storage directory.
     *
     * @return true if custom directory set, false if using default
     */
    fun hasCustomStorageDirectory(): Boolean
}
