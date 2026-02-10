package com.swaran.airbridge.domain.model

sealed class ServerStatus {
    data object Stopped : ServerStatus()
    data class Running(
        val address: String,
        val port: Int,
        val sessionToken: String
    ) : ServerStatus()
    data class Error(val message: String) : ServerStatus()
}
