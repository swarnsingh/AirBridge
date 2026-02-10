package com.swaran.airbridge.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

abstract class UseCase<in P, T, out R>(
    @Dispatcher(AirDispatchers.IO) val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend operator fun invoke(parameters: P): Flow<ResultState<out R>> = execute(parameters)
        .flowOn(coroutineDispatcher)

    protected abstract fun mapToDomain(data: T): R

    protected abstract suspend fun execute(parameters: P): Flow<ResultState<out R>>

    protected fun fetchResponse(
        block: suspend () -> T?,
    ): Flow<ResultState<out R>> = flow {
        try {
            emit(ResultState.Loading())
            val result = block()
            emit(ResultState.Success(mapToDomain(result as T)))
        } catch (e: Throwable) {
            emit(ResultState.Error(throwable = e))
        }
    }.flowOn(coroutineDispatcher)
}
