package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.common.ResultState
import com.swaran.airbridge.core.common.UseCase
import com.swaran.airbridge.domain.model.SessionInfo
import com.swaran.airbridge.domain.repository.ServerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class StartServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository,
    @Dispatcher(AirDispatchers.IO) dispatcher: CoroutineDispatcher
) : UseCase<StartServerUseCase.Params, SessionInfo, SessionInfo>(dispatcher) {

    data class Params(val port: Int = 8080)

    override fun mapToDomain(data: SessionInfo): SessionInfo = data

    override suspend fun execute(parameters: Params): Flow<ResultState<out SessionInfo>> =
        fetchResponse {
            serverRepository.startServer(parameters.port).getOrThrow()
        }
}
