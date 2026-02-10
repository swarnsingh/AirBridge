package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.common.ResultState
import com.swaran.airbridge.core.common.UseCase
import com.swaran.airbridge.domain.repository.StorageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import javax.inject.Inject

class DownloadFileUseCase @Inject constructor(
    private val storageRepository: StorageRepository,
    @Dispatcher(AirDispatchers.IO) dispatcher: CoroutineDispatcher
) : UseCase<DownloadFileUseCase.Params, InputStream, InputStream>(dispatcher) {

    data class Params(val fileId: String)

    override fun mapToDomain(data: InputStream): InputStream = data

    override suspend fun execute(parameters: Params): Flow<ResultState<out InputStream>> =
        fetchResponse {
            storageRepository.downloadFile(parameters.fileId).getOrThrow()
        }
}
