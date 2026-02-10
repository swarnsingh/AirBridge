package com.swaran.airbridge.core.network

import com.swaran.airbridge.core.network.controller.FileController
import com.swaran.airbridge.core.network.controller.HealthController
import com.swaran.airbridge.core.network.controller.PairingController
import com.swaran.airbridge.core.network.controller.StaticAssetController
import com.swaran.airbridge.core.network.controller.UploadController
import com.swaran.airbridge.core.network.controller.ZipController
import javax.inject.Inject

data class HttpControllers @Inject constructor(
    val fileController: FileController,
    val healthController: HealthController,
    val uploadController: UploadController,
    val zipController: ZipController,
    val staticAssetController: StaticAssetController,
    val pairingController: PairingController
)
