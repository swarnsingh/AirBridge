package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.ResultState
import kotlinx.coroutines.flow.Flow

/**
 * Use case to check battery optimization status.
 *
 * Battery optimization can kill the foreground service during long uploads,
 * breaking the WakeLock and causing transfer failures. This use case
 * checks if the app is exempt from battery optimization.
 */
abstract class CheckBatteryOptimizationStatusUseCase {

    /**
     * Check if battery optimization is enabled for this app.
     *
     * @return Flow emitting ResultState with true if optimization is enabled
     *         (meaning user needs to disable it), false if already exempt
     */
    abstract operator fun invoke(): Flow<ResultState<Boolean>>
}
