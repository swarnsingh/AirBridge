package com.swaran.airbridge.core.mvi

/**
 * Marker interface for MVI side-effect classes.
 *
 * One-time events like navigation, toasts, or dialogs should be represented
 * as sealed classes implementing this interface.
 */
interface MviEffect
