package com.swaran.airbridge.core.network.ktor

/**
 * Query parameter keys used across Ktor routes.
 *
 * Centralizing these prevents typos and makes refactoring easier.
 * All query parameters used by AirBridge HTTP endpoints are defined here.
 */
object QueryParams {
    
    /** Session token for authentication (required by most endpoints) */
    const val TOKEN = "token"
    
    /** File or directory path (e.g., "/", "/Download") */
    const val PATH = "path"
    
    /** File name for uploads */
    const val FILENAME = "filename"
    
    /** Unique upload ID for tracking/resume */
    const val UPLOAD_ID = "id"
    
    /** File ID for downloads */
    const val FILE_ID = "id"
    
    /** Comma-separated file IDs for zip download */
    const val FILE_IDS = "ids"
    
    /** Pairing session ID */
    const val PAIRING_ID = "id"
}

/**
 * JSON response field names used across Ktor routes.
 *
 * Centralizing these prevents typos and keeps client-server contract consistent.
 */
object ResponseFields {
    
    /** Success indicator boolean */
    const val SUCCESS = "success"
    
    /** Error message string */
    const val ERROR = "error"
    
    /** File exists indicator */
    const val EXISTS = "exists"
    
    /** File size in bytes */
    const val SIZE = "size"
    
    /** Upload/download status string */
    const val STATUS = "status"
    
    /** Paused indicator */
    const val IS_PAUSED = "isPaused"
    
    /** Unique upload ID */
    const val UPLOAD_ID = "uploadId"
    
    /** Bytes received so far */
    const val BYTES_RECEIVED = "bytesReceived"
    
    /** Array of file items */
    const val FILES = "files"
    
    /** File item ID */
    const val ID = "id"
    
    /** File name */
    const val NAME = "name"
    
    /** File path */
    const val PATH_FIELD = "path"
    
    /** File MIME type */
    const val MIME_TYPE = "mimeType"
    
    /** Is directory flag */
    const val IS_DIRECTORY = "isDirectory"
    
    /** Last modified timestamp */
    const val LAST_MODIFIED = "lastModified"
    
    /** Pairing ID */
    const val PAIRING_ID = "pairingId"
    
    /** Pairing approved boolean */
    const val APPROVED = "approved"
    
    /** Server status */
    const val SERVER = "server"
    
    /** Service name */
    const val SERVICE = "service"
    
    /** API version */
    const val VERSION = "version"
    
    /** Engine name (e.g., "ktor") */
    const val ENGINE = "engine"
    
    /** Session token for responses */
    const val TOKEN = "token"
    
    /** Pending push indicator */
    const val PENDING = "pending"
    
    /** File object field */
    const val FILE = "file"
    
    /** File URI field */
    const val URI = "uri"
    
    /** SHA-256 checksum of uploaded file */
    const val CHECKSUM = "checksum"
}
