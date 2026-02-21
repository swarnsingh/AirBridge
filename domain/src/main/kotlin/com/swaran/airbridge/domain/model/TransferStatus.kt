package com.swaran.airbridge.domain.model

enum class TransferStatus(val value: String) {
    UPLOADING("uploading"),
    COMPLETED("completed"),
    PAUSED("paused"),
    RESUMING("resuming"),
    CANCELLED("cancelled"),
    INTERRUPTED("interrupted"),
    ERROR("error");

    companion object {
        fun fromValue(value: String?): TransferStatus = 
            entries.find { it.value == value } ?: ERROR
    }
}
