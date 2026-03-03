package com.swaran.airbridge.domain.model

/**
 * UI-facing transfer status values.
 *
 * Maps internal UploadState to user-visible status strings.
 */
enum class TransferStatus(val value: String) {
    UPLOADING("uploading"),
    COMPLETED("completed"),
    PAUSED("paused"),
    PAUSING("pausing"),
    RESUMING("resuming"),
    QUEUED("queued"),
    CANCELLED("cancelled"),
    INTERRUPTED("interrupted"),
    ERROR("error");

    companion object {
        fun fromValue(value: String?): TransferStatus = 
            entries.find { it.value == value } ?: ERROR
    }
}
