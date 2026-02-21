package com.swaran.airbridge.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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

    private val _effect = Channel<E>(Channel.BUFFERED)

    /**
     * Observable effect flow for one-time events.
     */
    val effect = _effect.receiveAsFlow()

    private val _intent = Channel<I>(Channel.BUFFERED)

    init {
        viewModelScope.launch {
            _intent.receiveAsFlow().collect { intent ->
                handleIntent(intent)
            }
        }
    }

    /**
     * Sends an intent to be processed by the ViewModel.
     *
     * @param intent The intent to process
     */
    fun sendIntent(intent: I) {
        viewModelScope.launch {
            _intent.send(intent)
        }
    }

    /**
     * Updates the current state using the provided reducer function.
     *
     * @param reduce Function that transforms current state to new state
     */
    protected fun updateState(reduce: S.() -> S) {
        _state.value = _state.value.reduce()
    }

    /**
     * Sends a one-time effect to be consumed by the UI.
     *
     * @param effect The side effect to emit
     */
    protected fun sendEffect(effect: E) {
        viewModelScope.launch {
            _effect.send(effect)
        }
    }

    /**
     * Handles incoming intents and updates state/emits effects accordingly.
     *
     * @param intent The intent to handle
     */
    protected abstract suspend fun handleIntent(intent: I)
}
