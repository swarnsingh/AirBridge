package com.swaran.airbridge.core.service.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.network.upload.UploadScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Broadcast receiver for upload notification actions.
 */
@AndroidEntryPoint
class UploadActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var uploadScheduler: UploadScheduler

    @Inject
    @Dispatcher(AirDispatchers.IO)
    lateinit var ioDispatcher: CoroutineDispatcher

    @Inject
    lateinit var logger: AirLogger

    companion object {
        private const val TAG = "UploadActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UploadNotificationManager.ACTION_PAUSE_ALL -> {
                logger.d(TAG, "log", "Received PAUSE_ALL action")
                uploadScheduler.pauseAll()
            }
            UploadNotificationManager.ACTION_RESUME_ALL -> {
                logger.d(TAG, "log", "Received RESUME_ALL action")
                uploadScheduler.resumeAll()
            }
            UploadNotificationManager.ACTION_CANCEL_ALL -> {
                logger.d(TAG, "log", "Received CANCEL_ALL action")
                CoroutineScope(ioDispatcher).launch {
                    uploadScheduler.cancelAll()
                }
            }
        }
    }
}
