package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.common.ResultState
import com.swaran.airbridge.core.common.UseCase
import com.swaran.airbridge.domain.repository.ServerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetServerAddressUseCase @Inject constructor(
    private val serverRepository: ServerRepository,
    @Dispatcher(AirDispatchers.IO) dispatcher: CoroutineDispatcher
) : UseCase<Unit, String?, String?>(dispatcher) {

    override fun mapToDomain(data: String?): String? = data

    override suspend fun execute(parameters: Unit): Flow<ResultState<out String?>> =
        fetchResponse {
            serverRepository.getServerAddress()
        }
}
