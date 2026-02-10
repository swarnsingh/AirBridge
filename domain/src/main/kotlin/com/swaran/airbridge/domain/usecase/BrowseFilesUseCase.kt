package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.common.ResultState
import com.swaran.airbridge.core.common.UseCase
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.repository.StorageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class BrowseFilesUseCase @Inject constructor(
    private val storageRepository: StorageRepository,
    @Dispatcher(AirDispatchers.IO) dispatcher: CoroutineDispatcher
) : UseCase<BrowseFilesUseCase.Params, List<FileItem>, List<FileItem>>(dispatcher) {

    data class Params(val path: String = "/")

    override fun mapToDomain(data: List<FileItem>): List<FileItem> = data

    override suspend fun execute(parameters: Params): Flow<ResultState<out List<FileItem>>> =
        fetchResponse {
            storageRepository.browseFiles(parameters.path).getOrThrow()
        }
}
