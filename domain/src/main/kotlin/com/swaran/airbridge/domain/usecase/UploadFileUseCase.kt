package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.common.ResultState
import com.swaran.airbridge.core.common.UseCase
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.repository.StorageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import javax.inject.Inject

class UploadFileUseCase @Inject constructor(
    private val storageRepository: StorageRepository,
    @Dispatcher(AirDispatchers.IO) dispatcher: CoroutineDispatcher
) : UseCase<UploadFileUseCase.Params, FileItem, FileItem>(dispatcher) {

    data class Params(
        val path: String,
        val fileName: String,
        val inputStream: InputStream
    )

    override fun mapToDomain(data: FileItem): FileItem = data

    override suspend fun execute(parameters: Params): Flow<ResultState<out FileItem>> =
        fetchResponse {
            storageRepository.uploadFile(
                parameters.path,
                parameters.fileName,
                parameters.inputStream
            ).getOrThrow()
        }
}
