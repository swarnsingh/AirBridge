package com.swaran.airbridge.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

abstract class MviViewModel<I : MviIntent, S : MviState, E : MviEffect>(
    initialState: S
) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effect = Channel<E>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    private val _intent = Channel<I>(Channel.BUFFERED)

    init {
        viewModelScope.launch {
            _intent.receiveAsFlow().collect { intent ->
                handleIntent(intent)
            }
        }
    }

    fun sendIntent(intent: I) {
        viewModelScope.launch {
            _intent.send(intent)
        }
    }

    protected fun updateState(reduce: S.() -> S) {
        _state.value = _state.value.reduce()
    }

    protected fun sendEffect(effect: E) {
        viewModelScope.launch {
            _effect.send(effect)
        }
    }

    protected abstract suspend fun handleIntent(intent: I)
}
