package com.swaran.airbridge.core.network

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PushItem(
    val id: String,
    val fileName: String,
    val uri: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class PushManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _pendingPushes = MutableStateFlow<List<PushItem>>(emptyList())
    val pendingPushes: StateFlow<List<PushItem>> = _pendingPushes

    fun enqueuePush(fileName: String, uri: Uri) {
        val item = PushItem(
            id = System.currentTimeMillis().toString(),
            fileName = fileName,
            uri = uri.toString()
        )
        _pendingPushes.value = _pendingPushes.value + item
    }

    fun getNextPush(): PushItem? {
        val current = _pendingPushes.value
        if (current.isEmpty()) return null
        
        val item = current.first()
        _pendingPushes.value = current.drop(1)
        return item
    }
    
    fun peekPush(): PushItem? = _pendingPushes.value.firstOrNull()
}
