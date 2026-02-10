package com.swaran.airbridge.core.common

sealed class ResultState<out T> {
    data class Loading<T>(val data: T? = null) : ResultState<T>()
    data class Success<T>(val data: T) : ResultState<T>()
    data class Error<T>(val throwable: Throwable, val data: T? = null) : ResultState<T>()
}

fun <T> ResultState<T>.isLoading(): Boolean = this is ResultState.Loading
fun <T> ResultState<T>.isSuccess(): Boolean = this is ResultState.Success
fun <T> ResultState<T>.isError(): Boolean = this is ResultState.Error

fun <T> ResultState<T>.data(): T? = when (this) {
    is ResultState.Loading -> data
    is ResultState.Success -> data
    is ResultState.Error -> data
}
