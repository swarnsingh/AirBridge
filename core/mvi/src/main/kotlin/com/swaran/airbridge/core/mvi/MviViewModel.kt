package com.swaran.airbridge.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swaran.airbridge.core.common.ResultState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Base ViewModel class implementing the MVI (Model-View-Intent) pattern.
 *
 * @param I Type of intents (user actions)
 * @param S Type of state (UI state representation)
 * @param E Type of effects (one-time side effects)
 * @property initialState Initial state value
 */
abstract class MviViewModel<I : MviIntent, S : MviState, E : MviEffect>(
    initialState: S
) : ViewModel() {

    private val _state = MutableStateFlow(initialState)

    /**
     * Observable state flow for UI consumption.
     */
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<E>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /**
     * Observable effect flow for one-time events.
     * Uses SharedFlow so multiple collectors don't miss events.
     */
    val effect = _effect.asSharedFlow()

    private val _intent = MutableSharedFlow<I>(extraBufferCapacity = 64)

    init {
        viewModelScope.launch {
            _intent.collect { intent ->
                handleIntent(intent)
            }
        }
    }

    /**
     * Sends an intent to be processed by the ViewModel.
     */
    fun sendIntent(intent: I) {
        _intent.tryEmit(intent)
    }

    /**
     * Updates the current state using the provided reducer function.
     */
    protected fun updateState(reduce: S.() -> S) {
        _state.value = _state.value.reduce()
    }

    /**
     * Sends a one-time effect to be consumed by the UI.
     */
    protected fun sendEffect(effect: E) {
        _effect.tryEmit(effect)
    }

    /**
     * Handles ResultState flows with automatic state management.
     * Eliminates repetitive loading/success/error boilerplate.
     *
     * @param T The type of data in the ResultState
     * @param flow The flow of ResultState to handle
     * @param loadingState State to emit when loading
     * @param onSuccess Callback for success - returns Pair of (newState, optionalEffect)
     * @param onError Callback for error - returns Pair of (newState, optionalEffect)
     */
    protected suspend fun <T> handleResultState(
        flow: Flow<ResultState<T>>,
        loadingState: S,
        onSuccess: (T) -> Pair<S, E?>,
        onError: (Throwable) -> Pair<S, E?>
    ) {
        flow.collect { result ->
            when (result) {
                is ResultState.Loading -> {
                    updateState { loadingState }
                }
                is ResultState.Success -> {
                    val (newState, effect) = onSuccess(result.data)
                    updateState { newState }
                    effect?.let { sendEffect(it) }
                }
                is ResultState.Error -> {
                    val (newState, effect) = onError(result.throwable)
                    updateState { newState }
                    effect?.let { sendEffect(it) }
                }
            }
        }
    }

    /**
     * Handles incoming intents and updates state/emits effects accordingly.
     */
    protected abstract suspend fun handleIntent(intent: I)
}
